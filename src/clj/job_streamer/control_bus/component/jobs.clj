(ns job-streamer.control-bus.component.jobs
  (:require [clojure.tools.logging :as log]
            [clojure.edn :as edn]
            [com.stuartsierra.component :as component]
            [bouncer.validators :as v]
            [liberator.core :as liberator]
            (job-streamer.control-bus [notification :as notification]
                                      [validation :refer [validate]]
                                      [util :refer [parse-body edn->datoms to-int]])
            (job-streamer.control-bus.component [datomic :as d]
                                                [agents  :as ag]
                                                [scheduler :as scheduler]))
  (:import [java.util Date]))

(defn- find-latest-execution
  "Find latest from given executions."
  [executions]
  (when executions
    (->> executions
         (sort #(compare (:job-execution/create-time %2)
                         (:job-execution/create-time %1)))
         first)))

(defn- find-next-execution
  "Find next execution from a scheduler.
  If a job isn't scheduled, it returns nil."
  [{:keys [scheduler]} job]
  (when (:job/schedule job)
    (if-let [next-start (first (scheduler/fire-times scheduler (:db/id job)))]
      {:job-execution/start-time next-start})))

(defn extract-job-parameters [job]
  (->> (edn/read-string (:job/edn-notation job))
       (tree-seq coll? seq)
       (filter #(and (vector? %)
                     (keyword? (first %))
                     (= (name (first %)) "properties")
                     (map? (second %))))
       (map #(->> (second %)
                  (vals)
                  (map (fn [v] (->> (re-seq #"#\{([^\}]+)\}" v)
                                    (map second))))))
       (flatten)
       (map #(->> (re-seq #"jobParameters\['(\w+)'\]" %)
                  (map second)))
       (flatten)
       (apply hash-set)))

(defn find-undispatched [{:keys [datomic]}]
  (d/query
   datomic
   '{:find [?job-execution ?job-obj ?param-map]
     :where [[?job-execution :job-execution/batch-status :batch-status/undispatched]
             [?job-execution :job-execution/job-parameters ?parameter]
             [?job :job/executions ?job-execution]
             [?job :job/edn-notation ?edn-notation]
             [(clojure.edn/read-string ?edn-notation) ?job-obj]
             [(clojure.edn/read-string ?parameter) ?param-map]]}))

(defn find-by-name [{:keys [datomic]} app-name job-name]
  (d/query
   datomic
   '{:find [[?app ?job]]
     :in [$ ?app-name ?job-name]
     :where [[?app :application/name ?app-name]
             [?app :application/jobs ?job]
             [?job :job/name ?job-name]]}
   app-name job-name))


(defn find-all [{:keys [datomic]} app-name query & [offset limit]]
  (let [base-query '{:find [?job]
                     :in [$ ?app-name ?query]
                     :where [[?app :application/name ?app-name]
                             [?app :application/jobs ?job]]}
        jobs (->> (d/query datomic
                           (if (not-empty query)
                             (update-in base-query [:where]
                                        conj '[(fulltext $ :job/name ?query) [[?job ?job-name]]])
                             base-query)
                           app-name (or query ""))
                  (sort-by first)
                  reverse)]
    {:results (->> jobs
                   (drop (dec (or offset 0)))
                   (take (or limit 20))
                   (map #(->> (first %)
                              (d/pull datomic
                                      '[:*
                                        {(limit :job/executions 99999)
                                         [:db/id
                                          :job-execution/create-time
                                          :job-execution/start-time
                                          :job-execution/end-time
                                          :job-execution/exit-status
                                          {:job-execution/batch-status [:db/ident]}]}
                                        {:job/schedule
                                         [:db/id :schedule/cron-notation :schedule/active?]}])))
                   (map (fn [job]
                          (update-in job [:job/executions]
                                     (fn [executions]
                                       (->> executions
                                            (sort-by :job-execution/create-time #(compare %2 %1))
                                            (take 100)))) ))
                   vec)
     :hits   (count jobs)
     :offset offset
     :limit limit}))

(defn find-executions [{:keys [datomic]} app-name job-name & [offset limit]]
  (let [executions (d/query datomic
                            '{:find [?job-execution ?create-time]
                              :in [$ ?app-name ?job-name]
                              :where [[?job-execution :job-execution/create-time ?create-time]
                                      [?app :application/name ?app-name]
                                      [?app :application/jobs ?job]
                                      [?job :job/name ?job-name]
                                      [?job :job/executions ?job-execution]]}
                            app-name job-name)]
    {:results (->> executions
                   (sort-by second #(compare %2 %1))
                   (drop (dec offset))
                   (take limit)
                   (map #(d/pull datomic
                                 '[:db/id
                                   :job-execution/execution-id
                                   :job-execution/create-time
                                   :job-execution/start-time
                                   :job-execution/end-time
                                   :job-execution/job-parameters
                                   :job-execution/exit-status
                                   {:job-execution/batch-status [:db/ident]
                                    :job-execution/agent
                                    [:agent/instance-id :agent/name]}]
                                 (first %)))

                   vec)
     :hits    (count executions)
     :offset  offset
     :limit   limit}))

(defn find-execution [{:keys [datomic]} job-execution]
  (let [je (d/pull datomic
                   '[:*
                     {:job-execution/agent [:agent/instance-id :agent/name]}
                     {:job-execution/step-executions
                      [:*
                       {:step-execution/batch-status [:db/ident]}
                       {:step-execution/step [:step/name]}]}] job-execution)]
    (update-in je [:job-execution/step-executions]
               #(map (fn [step-execution]
                       (assoc step-execution
                         :step-execution/logs
                         (->> (d/query datomic
                                       '{:find [[(pull ?log [:* {:execution-log/level [:db/ident]}]) ...]]
                                         :in [$ ?step-execution-id ?instance-id]
                                         :where [[?log :execution-log/step-execution-id ?step-execution-id]
                                                 [?log :execution-log/agent ?agent]
                                                 [?agent :agent/instance-id ?instance-id]]}
                                       (:step-execution/step-execution-id step-execution)
                                       (get-in je [:job-execution/agent :agent/instance-id] ""))
                              (sort-by :execution-log/date compare)))) %))))

(defn find-step-execution [{:keys [datomic]} instance-id step-execution-id]
  (d/query datomic
           '{:find [?step-execution .]
             :in [$ ?instance-id ?step-execution-id]
             :where [[?job-execution :job-execution/agent ?agent]
                     [?agent :agent/instance-id ?instance-id]
                     [?job-execution :job-execution/step-executions ?step-execution]
                     [?step-execution :step-execution/step-execution-id ?step-execution-id]]}
           instance-id step-execution-id))


(defn save-execution [{:keys [datomic]} id execution]
  (log/debug "progress update: " id execution)
  (let [job (d/query datomic
                     '{:find [(pull ?job [:job/name
                                          {:job/steps [:step/name]}
                                          {:job/status-notifications
                                           [{:status-notification/batch-status [:db/ident]}
                                            :status-notification/exit-status
                                            :status-notification/type]}]) .]
                       :in [$ ?id]
                       :where [[?job :job/executions ?id]]} id)]
    (->> (:job/status-notifications job)
         (filter #(or (= (get-in % [:status-notification/batch-status :db/ident])
                         (:batch-status execution))
                      (= (:status-notification/exit-status %) (:exit-status execution))))
         (map #(notification/send (:status-notification/type %)
                                  (assoc execution :job-name (:job/name job))))
         doall))
  (d/transact datomic
              [(merge {:db/id id
                       :job-execution/batch-status (:batch-status execution)}
                      (when-let [exit-status (:exit-status execution)]
                        {:job-execution/exit-status exit-status})
                      (when-let [start-time (:start-time execution)]
                        {:job-execution/start-time start-time})
                      (when-let [end-time (:end-time execution)]
                        {:job-execution/end-time end-time}))]))

(defn- append-schedule [scheduler job-id executions schedule]
  (if (:schedule/active? schedule)
    (let [schedules (scheduler/fire-times scheduler job-id)]
      (apply conj executions
             (map (fn [sch]
                    {:job-execution/start-time sch
                     :job-execution/end-time (Date. (+ (.getTime sch) (* 5 60 1000)))
                     :job-execution/batch-status {:db/ident :batch-status/registered}}) schedules)))
    executions))


(defn list-resource [{:keys [datomic scheduler] :as jobs} app-name]
  (liberator/resource
   :available-media-types ["application/edn"]
   :allowed-methods [:get :post]
   :malformed? #(validate (parse-body %)
                          :job/name [v/required [v/matches #"^[\w\-]+$"]])
   :exists? (fn [{{job-name :job/name} :edn :as ctx}]
              (if (#{:post} (get-in ctx [:request :request-method]))
                (when-let [[_ job-id] (find-by-name jobs app-name job-name)]
                  {:job-id job-id})
                true))
   :post! (fn [{job :edn job-id :job-id}]
            (let [datoms (edn->datoms job job-id)
                  job-id (:db/id (first datoms))]
              (d/transact datomic
                          (conj datoms
                                [:db/add [:application/name app-name] :application/jobs job-id]))
              job))
   :handle-ok (fn [{{{query :q with-param :with :keys [limit offset]} :params} :request}]
                (let [js (find-all jobs app-name query
                                   (to-int offset 0)
                                   (to-int limit 20))
                      with-params (->> (clojure.string/split
                                        (or (not-empty with-param) "execution")
                                        #"\s*,\s*")
                                       (map keyword)
                                       set)]
                  (update-in js [:results]
                             #(->> %
                                   (map (fn [{job-name :job/name
                                              executions :job/executions
                                              schedule :job/schedule :as job}]
                                          (merge {:job/name job-name}
                                                 (when (with-params :execution)
                                                   {:job/executions (append-schedule scheduler (:db/id job) executions schedule)
                                                    :job/latest-execution (find-latest-execution executions)
                                                    :job/next-execution   (find-next-execution jobs job)})
                                                 (when (with-params :schedule)
                                                   {:job/schedule schedule})
                                                 (when (with-params :notation)
                                                   {:job/edn-notation (:job/edn-notation job)})
                                                 (when (with-params :settings)
                                                   (merge {:job/exclusive? (get job :job/exclusive? false)}
                                                          (when-let [time-monitor (get-in job [:job/time-monitor :db/id])]
                                                            {:job/time-monitor (d/pull datomic
                                                                                       '[:time-monitor/duration
                                                                                         {:time-monitor/action [:db/ident]}
                                                                                         :time-monitor/notification-type] time-monitor)})
                                                          (when-let [status-notifications (:job/status-notifications job)]
                                                            {:job/status-notifications (->> status-notifications
                                                                                            (map (fn [sn]
                                                                                                   (d/pull datomic
                                                                                                           '[{:status-notification/batch-status [:db/ident]}
                                                                                                             :status-notification/exit-status
                                                                                                             :status-notification/type] (:db/id sn))))
                                                                                            vec)}))))))
                                   vec))))))


(defn entry-resource [{:keys [datomic scheduler] :as jobs} app-name job-name]
  (liberator/resource
   :available-media-types ["application/edn"]
   :allowed-methods [:get :put :delete]
   :malformed? #(parse-body %)
   :exists? (when-let [[app-id job-id] (find-by-name jobs app-name job-name)]
              {:app-id app-id
               :job-id job-id})
   :put! (fn [{job :edn job-id :job-id}]
           (d/transact datomic (edn->datoms job job-id)))
   :delete! (fn [{job-id :job-id app-id :app-id}]
              (scheduler/unschedule job-id)
              (d/transact datomic
                          [[:db.fn/retractEntity job-id]
                           [:db/retract app-id :application/jobs job-id]]))
   :handle-ok (fn [ctx]
                (let [job (d/pull datomic
                                  '[:*
                                    {(limit :job/executions 99999)
                                     [:db/id
                                      :job-execution/start-time
                                      :job-execution/end-time
                                      :job-execution/create-time
                                      :job-execution/exit-status
                                      {:job-execution/batch-status [:db/ident]}
                                      {:job-execution/agent [:agent/name :agent/instance-id]}]}
                                    {:job/schedule [:schedule/cron-notation :schedule/active?]}]
                                  (:job-id ctx))
                      total (count (:job/executions job))
                      success (->> (:job/executions job)
                                   (filter #(= (get-in % [:job-execution/batch-status :db/ident])
                                               :batch-status/completed))
                                   count)
                      failure (->> (:job/executions job)
                                   (filter #(= (get-in % [:job-execution/batch-status :db/ident])
                                               :batch-status/failed))
                                   count)
                      average (if (= success 0) 0
                                (/ (->> (:job/executions job)
                                        (filter #(= (get-in % [:job-execution/batch-status :db/ident])
                                                    :batch-status/completed))
                                        (map #(- (.getTime (:job-execution/end-time %))
                                                 (.getTime (:job-execution/start-time %))))
                                        (reduce +))
                                   success))]
                  (-> job
                      (assoc :job/stats {:total total :success success :failure failure :average average}
                        :job/latest-execution (find-latest-execution (:job/executions job))
                        :job/next-execution   (find-next-execution jobs job)
                        :job/dynamic-parameters (extract-job-parameters job))
                      (dissoc :job/executions))))))

(defn job-settings-resource [{:keys [datomic] :as jobs} app-name job-name & [cmd]]
  (liberator/resource
   :available-media-types ["application/edn"]
   :allowed-methods [:get :delete :put]
   :malformed? #(parse-body %)
   :exists? (when-let [[app-id job-id] (find-by-name jobs app-name job-name)]
              {:app-id app-id
               :job-id job-id})
   :put! (fn [{settings :edn job-id :job-id}]
           (case cmd
             :exclusive (d/transact datomic
                                    [{:db/id job-id
                                      :job/exclusive? true}])

             :status-notification
             (if-let [id (:db/id settings)]
               (d/transact datomic
                           [[:db/retract job-id :job/status-notifications id]])
               (let [status-notification-id (d/tempid :db.part/user)
                     tempids (-> (d/transact
                                  datomic
                                  [[:db/add job-id
                                    :job/status-notifications status-notification-id]
                                   (merge {:db/id status-notification-id
                                           :status-notification/type (:status-notification/type settings)}
                                          (when-let [batch-status (:status-notification/batch-status settings)]
                                            {:status-notification/batch-status batch-status})
                                          (when-let [exit-status (:status-notification/exit-status settings)]
                                            {:status-notification/exit-status exit-status}))])
                                 :tempids)]
                 {:db/id (d/resolve-tempid datomic tempids status-notification-id)}))

             :time-monitor
             (d/transact datomic
                         [(merge {:db/id #db/id[db.part/user -1]} settings)
                          {:db/id job-id :job/time-monitor #db/id[db.part/user -1]}])))

   :delete! (fn [{settings :edn job-id :job-id}]
              (case cmd
                :exclusive (d/transact datomic [{:db/id job-id
                                                 :job/exclusive? false}])
                :time-monitor
                (when-let [time-monitor-id (some-> (d/pull datomic
                                                           '[:job/time-monitor] job-id)
                                                   :job/time-monitor
                                                   :db/id)]
                  (d/transact datomic
                              [[:db/retract job-id
                                :job/time-monitor time-monitor-id]
                               [:db.fn/retractEntity time-monitor-id]]))))

   :handle-created (fn [ctx]
                     (select-keys ctx [:db/id]))

   :handle-ok (fn [ctx]
                (let [settings (d/pull datomic
                                       '[:job/exclusive?
                                         {:job/time-monitor
                                          [:time-monitor/duration
                                           {:time-monitor/action [:db/ident]}
                                           :time-monitor/notification-type]}
                                         {:job/status-notifications
                                          [:db/id
                                           {:status-notification/batch-status [:db/ident]}
                                           :status-notification/exit-status
                                           :status-notification/type]}]
                                       (:job-id ctx))]
                  (-> settings
                      (update-in
                       [:job/status-notifications]
                       (fn [notifications]
                         (map #(assoc %
                                 :status-notification/batch-status
                                 (get-in % [:status-notification/batch-status :db/ident])) notifications)))
                      (update-in [:job/time-monitor]
                                 (fn [time-monitor]
                                   (when-let [action (get-in time-monitor [:time-monitor/action :db/ident])]
                                     (assoc time-monitor :time-monitor/action action)))))))))

(defn- execute-job [{:keys [datomic scheduler] :as jobs} app-name job-name ctx]
  (when-let [[app-id job-id] (find-by-name jobs app-name job-name)]
    (let [execution-id (d/tempid :db.part/user)
          tempids (-> (d/transact
                       datomic
                       [{:db/id execution-id
                         :job-execution/batch-status :batch-status/undispatched
                         :job-execution/create-time (java.util.Date.)
                         :job-execution/job-parameters (pr-str (or (:edn ctx) {}))}
                        [:db/add job-id :job/executions execution-id]])
                      :tempids)]
      (when-let [time-monitor (d/pull datomic
                                      '[{:job/time-monitor
                                         [:time-monitor/duration
                                          {:time-monitor/action [:db/ident]}]}]
                                      job-id)]
        (scheduler/time-keeper
         scheduler
         (d/resolve-tempid datomic tempids execution-id)
         (get-in time-monitor [:job/time-monitor :time-monitor/duration])
         (get-in time-monitor [:job/time-monitor :time-monitor/action :db/ident]))))))

(defn executions-resource [{:keys [datomic] :as jobs} app-name job-name]
  (liberator/resource
   :available-media-types ["application/edn"]
   :allowed-methods [:get :post]
   :malformed? #(parse-body %)
   :exists? (when-let [[app-id job-id] (find-by-name jobs app-name job-name)]
              {:job (d/pull datomic
                            '[:job/exclusive?
                              {:job/executions
                               [:job-execution/create-time
                                {:job-execution/batch-status [:db/ident]}]}] job-id)})
   :post-to-existing? (fn [ctx]
                        (when (#{:put :post} (get-in ctx [:request :request-method]))
                          (not (:job/exclusive? (:job ctx)))))
   :put-to-existing? (fn [ctx]
                       (#{:put :post} (get-in ctx [:request :request-method])))
   :conflict? (fn [{job :job}]
                (if-let [last-execution (some->> (:job/executions job)
                                                 (sort #(compare (:job-execution/create-time %2) (:job-execution/create-time %1)))
                                                 first)]
                  (contains? #{:batch-status/undispatched
                               :batch-status/queued
                               :batch-status/starting
                               :batch-status/started
                               :batch-status/stopping}
                             (get-in last-execution [:job-execution/batch-status :db/ident]))
                  false))
   :put!  #(execute-job jobs app-name job-name %)
   :post! #(execute-job jobs app-name job-name %)
   :handle-ok (fn [{{{:keys [offset limit]} :params} :request}]
                (find-executions jobs app-name job-name
                                 (to-int offset 0)
                                 (to-int limit 20)))))

(defn execution-resource [{:keys [agents scheduler datomic] :as jobs} id & [cmd]]
  (liberator/resource
   :available-media-types ["application/edn"]
   :allowed-methods [:get :put]
   :malformed? #(parse-body %)
   :exists? (fn [ctx]
              (when-let [execution (d/pull datomic
                                           '[:*
                                             {:job-execution/agent
                                              [:db/id :agent/instance-id]}]
                                           id)]
                (when-let [job-id (d/query datomic
                                           '{:find [?job .]
                                             :in [$ ?eid]
                                             :where [[?job :job/executions ?eid]]}
                                           id)]
                  {:execution execution
                   :job-id job-id})))

   :put! (fn [{parameters :edn execution :execution job-id :job-id}]
           (case cmd
             :abandon (ag/abandon-execution
                       agents
                       execution
                       :on-success (fn [_]
                                     (ag/update-execution-by-id
                                      id
                                      :on-success (fn [response]
                                                    (save-execution jobs id response))
                                      :on-error (fn [error]
                                                  (log/error error)))))

             :stop (ag/stop-execution
                    agents
                    execution
                    :on-success (fn [_]
                                  (ag/update-execution-by-id
                                   id
                                   :on-success (fn [response]
                                                 (save-execution jobs id response)))))

             :restart (let [execution-id (d/tempid :db.part/user)
                            tempids (-> (d/transact
                                         datomic
                                         [{:db/id execution-id
                                           :job-execution/batch-status :batch-status/unknown
                                           :job-execution/create-time (java.util.Date.)
                                           :job-execution/agent (:job-execution/agent execution)
                                           :job-execution/job-parameters (pr-str (or parameters {}))}
                                          [:db/add job-id :job/executions execution-id]])
                                        :tempids)
                            new-id (d/resolve-tempid datomic tempids execution-id)]
                        (when-let [time-monitor (d/pull datomic
                                                        '[{:job/time-monitor
                                                           [:time-monitor/duration
                                                            {:time-monitor/action [:db/ident]}]}]
                                                        job-id)]
                          (scheduler/time-keeper
                           scheduler new-id
                           (get-in time-monitor [:job/time-monitor :time-monitor/duration])
                           (get-in time-monitor [:job/time-monitor :time-monitor/action :db/ident])))
                        (ag/restart-execution
                         agents execution
                         :on-success (fn [resp]
                                       (d/transact datomic
                                                   [{:db/id new-id
                                                     :job-execution/execution-id (:execution-id resp)
                                                     :job-execution/batch-status (:batch-status resp)
                                                     :job-execution/start-time   (:start-time   resp)}])
                                       (ag/update-execution-by-id
                                        agents
                                        new-id
                                        :on-success (fn [new-exec]
                                                      (save-execution jobs new-id new-exec))))))


             :alert (let [job (d/query datomic
                                       '{:find [(pull ?job [:job/name
                                                            {:job/time-monitor
                                                             [:time-monitor/notification-type]}]) .]
                                         :in [$ ?id]
                                         :where [[?job :job/executions ?id]]} id)]
                      (notification/send
                       (get-in job [:job/time-monitor :time-monitor/notification-type])
                       {:job-name (:job/name job)
                        :duration (get-in job [:job/time-monitor :time-monitor/duration])}))
             nil))
   :handle-ok (fn [ctx]
                (find-execution jobs id))))

(defrecord Jobs []
  component/Lifecycle

  (start [component]
         component)

  (stop [component]
        (dissoc component :list-resource :entry-resource)))

(defn jobs-component [options]
  (map->Jobs options))

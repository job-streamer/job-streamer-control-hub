(ns job-streamer.control-bus.component.migration
  (:require [com.stuartsierra.component :as component]
            [datomic.api :refer [part]]
            [datomic-schema.schema :as s]
            [clojure.tools.logging :as log]
            (job-streamer.control-bus.component [datomic :as d])))

(defn- generate-enums [& enums]
  (apply concat
         (map #(s/get-enums (name (first %))
                            :db.part/user (second %)) enums)))

(defn- dbparts []
  [(part "job")])

(defn roll-migration [{:keys [datomic]}]
  (let [rolling-members (d/query datomic
                                '{:find [[(pull ?member [:db/id
                                                         {:member/user [:user/id]}
                                                         {:member/rolls [:roll/name]}]) ...]]
                                  :in [$]
                                  :where [[?member :member/rolls]
                                           (not-join [?member]
                                             [?member :member/roles])]})
        roles (d/query datomic
                      '{:find [[(pull ?role [:db/id :role/name]) ...]]
                        :in [$]
                        :where [[?role :role/name]]})
        role-name->id (fn [name]
                        (-> (filter #(= name (:role/name %)) roles)
                            first
                            :db/id))]
    (when (not-empty rolling-members)
      (->> rolling-members
           (map #(map (fn [{roll-name :roll/name}]
                        {:role/id (role-name->id roll-name)
                         :member/id (:db/id %)})
                      (:member/rolls %)))
           flatten
           (map (fn [{member :member/id role :role/id}]
                  [:db/add member :member/roles role]))
           (d/transact datomic))
      (->> (map #(get-in % [:member/user :user/id]) rolling-members)
           (log/info "Migrated users rolls to roles:")))))

(defrecord Migration [datomic dbschema]
  component/Lifecycle

  (start [component]
    (let [schema (concat
                  ;(s/generate-parts (dbparts))
                  (generate-enums [:batch-status [:undispatched :unrestarted :queued
                                                  :abandoned :completed :failed
                                                  :started :starting :stopped :stopping
                                                  :unknown]]
                                  [:log-level [:trace :debug :info :warn :error]]
                                  [:action [:abandon :stop :alert]])
                  (s/generate-schema dbschema))]
      (d/transact datomic schema)
      component))

  (stop [component]
    component))

(defn migration-component [options]
  (map->Migration options))

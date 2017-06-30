(ns job-streamer.control-bus.endpoint.api
  "Define resources for web api."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [compojure.core :refer [ANY GET POST routes]]
            [bouncer.validators :as v]
            [ring.util.response :refer [content-type]]
            [datomic.api :as d]
            (job-streamer.control-bus.component
             [apps :as apps]
             [jobs :as jobs]
             [agents :refer [find-agent available-agents] :as ag]
             [scheduler :as scheduler]
             [calendar :as calendar]
             [auth :as auth])
            (job-streamer.control-bus
             [model :as model]
             [notification :as notification]
             [validation :refer [validate]]
             [util :refer [parse-body]])))

(defn api-endpoint [{:keys [jobs agents calendar scheduler apps auth datomic]}]
  (routes
   ;; Auth
   (ANY "/auth" request (auth/auth-resource auth))
   (ANY "/users" [] (auth/list-resource auth))
   (ANY "/user" [] (auth/entry-resource auth nil))
   (ANY ["/user/:user-id" :user-id #".*"] [user-id] (auth/entry-resource auth user-id))
   (ANY "/oauth" request (auth/redirect-to-auth-provider auth request))
   (ANY "/oauth/cb" request (auth/oauth-resource auth request))

   ;; Job
   (ANY "/:app-name/jobs" [app-name]
     (jobs/list-resource jobs app-name))
   (ANY "/:app-name/jobs/download" [app-name]
     (jobs/list-resource jobs app-name :download? true))
   (ANY ["/:app-name/job/:job-name/settings/:cmd"
         :app-name #".*"
         :job-name #".*"
         :cmd #"[\w\-]+"]
       [app-name job-name cmd]
     (jobs/job-settings-resource jobs app-name job-name (keyword cmd)))
   (ANY ["/:app-name/job/:job-name/settings" :app-name #".*" :job-name #".*"]
       [app-name job-name]
     (jobs/job-settings-resource jobs app-name job-name))
   (ANY ["/:app-name/job/:job-name/executions" :app-name #".*" :job-name #".*"]
       [app-name job-name]
     (jobs/executions-resource jobs app-name job-name))

   (ANY "/test-executions"
        []
        (jobs/test-executions-resource jobs))
   (ANY ["/test-execution/:id" :id #"\d+"]
        [id]
        (jobs/test-execution-resource jobs (Long/parseLong id)))

   ;; Scheduler
   (ANY ["/:app-name/job/:job-name/schedule" :app-name #".*" :job-name #".*"]
       [app-name job-name]
     (let [[_ job-id] (jobs/find-by-name jobs app-name job-name)]
       (scheduler/entry-resource scheduler job-id)))
   (ANY ["/:app-name/job/:job-name/schedule/:cmd" :app-name #".*" :job-name #".*" :cmd #"\w+"]
       [app-name job-name cmd]
     (let [[_ job-id] (jobs/find-by-name jobs app-name job-name)]
       (scheduler/entry-resource scheduler job-id (keyword cmd))))

   (ANY ["/:app-name/job/:job-name/execution/:id" :app-name #".*" :job-name #".*" :id #"\d+"]
       [app-name job-name id]
     (jobs/execution-resource jobs (Long/parseLong id)))
   (ANY ["/:app-name/job/:job-name/execution/:id/:cmd" :app-name #".*" :job-name #".*" :id #"\d+" :cmd #"\w+"]
       [app-name job-name id cmd]
     (jobs/execution-resource jobs (Long/parseLong id) (keyword cmd)))
   (ANY ["/:app-name/job/:job-name" :app-name #".*" :job-name #".*"]
       [app-name job-name] (jobs/entry-resource jobs app-name job-name))

   ;; Calendar
   (ANY ["/calendar/:cal-name" :cal-name #".*"] [cal-name]
     (calendar/entry-resource calendar cal-name))
   (ANY "/calendars" [] (calendar/list-resource calendar))
   (ANY "/calendars/download" [] (calendar/list-resource calendar :download? true))

   ;; Agents
   (ANY "/agents" [] (ag/list-resource agents))
   (ANY ["/agent/:instance-id/:cmd" :instance-id #"[A-Za-z0-9\-]+" :cmd #"\w+"]
       [instance-id cmd]
     (ag/entry-resource agents instance-id (keyword cmd)))

   (ANY "/agent/:instance-id" [instance-id]
     (ag/entry-resource agents instance-id))
   (ANY "/agent/:instance-id/monitor/:type/:cycle" [instance-id type cycle]
     (ag/monitor-resource agents instance-id type cycle))

   ;; Applications
   (ANY "/apps" [] (apps/list-resource apps))
   (ANY ["/:app-name/batch-components" :app-name #".*"]
       [app-name]
     (apps/batch-components-resource apps app-name))
   (ANY "/:app-name/stats" [app-name]
     (apps/stats-resource apps app-name))
   (GET "/version" [] (-> {:body  (clojure.string/replace (str "\"" (slurp "VERSION") "\"") "\n" "")}
                                       (content-type "text/plain")))
   ;; Health Check
   (GET "/healthcheck" []
     (try
       (d/connect (:uri datomic))
       {:status 200}
       (catch Exception e {:status 503})))

   ;; For debug
   ;(GET "/logs" [] (pr-str (model/query '{:find [[(pull ?log [*]) ...]] :where [[?log :execution-log/level]]})))
   ))

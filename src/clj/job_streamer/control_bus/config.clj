(ns job-streamer.control-bus.config
  (:require [environ.core :refer [env]]
            [job-streamer.control-bus.model :as model]))

(def defaults
  {:http {:port 45102}
   :app {:same-origin {:access-control-allow-origin "http://localhost:3000"}}
   :discoverer {:ws-port 45102}
   :scheduler  {:host "localhost"
                :port 45102}
   :migration {:dbschema model/dbschema}
   :token {:cache-time (* 30 60 1000)}
   :auth {:access-control-allow-origin "http://localhost:3000"
          :console-uri "http://localhost:3000"
          :auth-endpoint {:query {:response_type "code"
                                  :redirect_uri "http://localhost:45102/oauth/cb"
                                  :scope "default"}}
          :token-endpoint {:query {:grant_type "authorization_code"
                                   :redirect_uri "http://localhost:45102/oauth/cb"}}
          :introspection-endpoint {:query {:token_type_hint "access_token"}}}
   :datomic {:uri "datomic:free://172.24.34.214:4334/job-streamer"}})

(def environ
  (let [port (some-> env :control-bus-port Integer.)
        datomic-uri (:datomic-uri env)
        access-control-allow-origin (some-> env :access-control-allow-origin)
        console-uri access-control-allow-origin
        oauth? (some-> env :oauth Boolean.)
        redirect-uri (some-> env :domain (str "/oauth/cb"))
        auth-uri (some-> env :auth-uri)
        scope (some-> env :scope)
        client-id (some-> env :client-id)
        token-uri (some-> env :token-uri)
        introspection-uri (some-> env :introspection-uri)
        cache-time (some-> env :token-cache-time Integer. (* 60 1000))]
  {:http {:port port}
   :app {:same-origin {:access-control-allow-origin access-control-allow-origin}}
   :discoverer {:ws-port port}
   :scheduler  {:host "localhost"
                :port port}
   :token {:cache-time cache-time}
   :auth {:access-control-allow-origin access-control-allow-origin
          :console-uri console-uri
          :oauth? oauth?
          :auth-endpoint {:url auth-uri
                          :query {:client_id client-id
                                  :redirect_uri redirect-uri
                                  :scope scope}}
          :token-endpoint {:url token-uri
                           :query {:redirect_uri redirect-uri
                                   :client_id client-id}}
          :introspection-endpoint {:url introspection-uri}}
   :datomic {:uri datomic-uri}}))


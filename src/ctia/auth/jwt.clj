(ns ctia.auth.jwt
  (:refer-clojure :exclude [identity])
  (:require [clj-momo.lib.set :refer [as-set]]
            [clojure
             [set :as set]
             [string :as string]]
            [ctia
             [auth :as auth :refer [IIdentity]]
             [properties :as prop]]
            [ctia.auth.capabilities
             :refer
             [all-entities gen-capabilities-for-entity-and-accesses]]
            [ring-jwt-middleware.core :as mid]))

(defn entity-root-scope []
  (get-in @prop/properties [:ctia :auth :entities :scope]
          "private-intel"))

(defn casebook-root-scope []
  (get-in @prop/properties [:ctia :auth :casebook :scope]
          "casebook"))

(def claim-prefix
  (get-in @prop/properties [:ctia :http :jwt :claim-prefix]
          "https://schemas.cisco.com/iroh/identity/claims"))

(defn unionize
  "Given a seq of set make the union of all of them"
  [sets]
  (apply set/union sets))

(defn gen-entity-capabilities
  "given a scope representation whose root scope is entity-root-scope generate
  capabilities"
  [scope-repr]
  (case (count (:path scope-repr))
    ;; example: ["private-intel" "sighting"] (for private-intel/sighting scope)
    2 (condp = (second (:path scope-repr))
        "import-bundle" (if (contains? (:access scope-repr) :write)
                          #{:import-bundle}
                          #{})
        (let [entity (get all-entities
                          (-> scope-repr
                              :path
                              second
                              keyword))]
          (gen-capabilities-for-entity-and-accesses
           entity
           (:access scope-repr))))
    ;; typically: ["private-intel"]
    1 (->> all-entities
           vals
           (map #(gen-capabilities-for-entity-and-accesses % (:access scope-repr)))
           unionize
           (set/union (if (contains? (:access scope-repr) :write)
                        #{:import-bundle}
                        #{})))
    #{}))

(defn gen-casebook-capabilities
  "given a scope representation whose root-scope is casebook generate
  capabilities"
  [scope-repr]
  (gen-capabilities-for-entity-and-accesses
   (:casebook all-entities)
   (:access scope-repr)))

(defn scope-to-capabilities
  "given a scope generate capabilities"
  [scope]
  (let [scope-repr (mid/to-scope-repr scope)]
    (condp = (first (:path scope-repr))
      (entity-root-scope)   (gen-entity-capabilities scope-repr)
      (casebook-root-scope) (gen-casebook-capabilities scope-repr)
      #{})))

(defn scopes-to-capabilities
  "given a seq of scopes generate a set of capabilities"
  [scopes]
  (->> scopes
       (map scope-to-capabilities)
       unionize))

(defn iroh-claim
  "JWT specific claims for iroh are URIs

  For example:

  https://schemas.cisco.com/iroh/identity/claims/user/id
  https://schemas.cisco.com/iroh/identity/claims/user/name
  https://schemas.cisco.com/iroh/identity/claims/user/email
  https://schemas.cisco.com/iroh/identity/claims/org/id
  https://schemas.cisco.com/iroh/identity/claims/roles

  See https://github.com/threatgrid/iroh/issues/1707

  Note iroh-claim are strings not keywords because from
  https://clojure.org/reference/reader
  '/' has special meaning.
  "
  [keyword-name]
  (str claim-prefix "/" keyword-name))

(defn unlimited-clientids
  "Retrieves and parses unlimited clientids defined in the properties"
  []
  (some-> (get-in @prop/properties
                  [:ctia :http :rate-limit :unlimited :client-ids])
          (string/split #",")
          set))

(defn parse-unlimited-props
  []
  (let [clientids (unlimited-clientids)]
    (cond-> {}
      (seq clientids) (assoc :clienids clientids))))

(defrecord JWTIdentity [jwt unlimited-fn]
  IIdentity
  (authenticated? [_]
    true)
  (login [_]
    (:sub jwt))
  (groups [_]
    (remove nil? [(get jwt (iroh-claim "org/id"))]))
  (allowed-capabilities [_]
    (let [scopes (set (get jwt (iroh-claim "scopes")))]
      (scopes-to-capabilities scopes)))
  (capable? [this required-capabilities]
    (set/subset? (as-set required-capabilities)
                 (auth/allowed-capabilities this)))
  (rate-limit-fn [_ limit-fn]
    (when (not (unlimited-fn jwt))
      limit-fn)))

(defn unlimited?
  [unlimited-properties jwt]
  (let [clientid (get jwt (iroh-claim "oauth/client/id"))
        unlimited-clientids (get unlimited-properties :clientids)]
    (contains? unlimited-clientids clientid)))

(defn wrap-jwt-to-ctia-auth
  [handler]
  (let [unlimited-properties (parse-unlimited-props)]
    (fn [request]
      (handler
       (if-let [jwt (:jwt request)]
         (let [identity
               (->JWTIdentity jwt (partial unlimited? unlimited-properties))]
           (assoc request
                  :identity identity
                  :login    (auth/login identity)
                  :groups   (auth/groups identity)))
         request)))))

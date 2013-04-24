(ns clj-r53.client
  (:refer-clojure :exclude [find])
  (:require [clojure.zip :as zip]
            [clojure.data.zip :as zf]
            [clojure.data.zip.xml :as zf-xml]
            [clj-r53.core :refer (endpoint r53-fn require-arg)]
            [arohner.map :refer (submap?)]
            [arohner.utils :refer (inspect)]))

(defn- create-hosted-zone-request [{:keys [name ref comment]}]
  [:CreateHostedZoneRequest {:xmlns "https://route53.amazonaws.com/doc/2011-05-05/"}
   [:Name name]
   [:CallerReference ref]
   (when comment
     [:HostedZoneConfig
      [:Comment comment]])])

(defn create-hosted-zone
  "Create a new hosted zone. name is required. Returns the whole response, with the body parsed."
  [account & {:keys [name ref comment] :as args}]
  (require-arg "name" name)
  (r53-fn account {:method :post
                   :url (str endpoint "/hostedzone")
                   :body (create-hosted-zone-request args)}))

(defn hosted-zone-url->id
  "converts /hostedzone/foo -> foo"
  [zone-url]
  (->
   (re-find #"/hostedzone/(.*)" zone-url)
   (get 1)))

(defn list-hosted-zones
  "returns a map of domain names to zone-ids"
  [account]
  (let [resp (r53-fn account
                     {:method :get
                      :url (str endpoint "/hostedzone")})]
    (when resp
      (let [zipper (-> resp
                       :body
                       (zip/xml-zip))]
        (into {} (map (fn [name zone-url]
                        [name (hosted-zone-url->id zone-url)])
                      (zf-xml/xml->
                       zipper
                       :HostedZones
                       :HostedZone
                       :Name
                       zf-xml/text)
                      (zf-xml/xml->
                       zipper
                       :HostedZones
                       :HostedZone
                       :Id
                       zf-xml/text)))))))

(defn- change-resource-record-set
  [account zone-id request-xml]
  (r53-fn account
          {:method :post
           :url (str endpoint "/hostedzone/" zone-id "/rrset")
           :body request-xml}))

(defn with-r53-transaction
  "Executes a series of DNS record changes inside a transaction. Exprs is a seq of r53 xml strings"
  [account zone-id & exprs]
  (change-resource-record-set
   account zone-id
   [:ChangeResourceRecordSetsRequest {:xmlns "https://route53.amazonaws.com/doc/2011-05-05/"}
    [:ChangeBatch
     [:Changes
      exprs]]]))

(defn change [{:keys [action type name ttl value alias-zone-id]}]
  [:Change
   [:Action action]
   [:ResourceRecordSet
    [:Name name]
    [:Type type]
    (when-not alias-zone-id
      [:TTL ttl])
    (if alias-zone-id
      [:AliasTarget
       [:HostedZoneId alias-zone-id]
       [:DNSName value]]
      `[:ResourceRecords
        ~@(if (sequential? value)
            (map (fn [val]
                   [:ResourceRecord
                    [:Value val]])
                 value)
            [[:ResourceRecord
              [:Value value]]])])]])

(defn create-r53-entry
  [type & {:keys [name value ttl comment]}]
  (require-arg "name" name)
  (require-arg "value" value)
  (require-arg "ttl" ttl)
  (change {:action "CREATE"
           :type type
           :name name
           :ttl ttl
           :value value}))

(defn create-A-name
  "Returns the XML to create a new A name record. For use inside (with-r53-transaction)"
  [& {:keys [name value ttl comment]}]
  (require-arg "name" name)
  (require-arg "value" value)
  (require-arg "ttl" ttl)
  (create-r53-entry "A" :name name :value value :ttl ttl :comment comment))

(defn create-CNAME
  [& {:keys [name value ttl comment]}]
  (require-arg "name" name)
  (require-arg "value" value)
  (require-arg "ttl" ttl)
  (create-r53-entry "CNAME" :name name :value value :ttl ttl :comment comment))

(defn create-TXT
  [& {:keys [name value ttl comment]}]
  (require-arg "name" name)
  (require-arg "value" value)
  (require-arg "ttl" ttl)
  (create-r53-entry "TXT" :name name :value value :ttl ttl :comment comment))

(defn create-MX
  [& {:keys [name value ttl comment]}]
  (require-arg "name" name)
  (require-arg "value" value)
  (require-arg "ttl" ttl)
  (create-r53-entry "MX" :name name :value value :ttl ttl :comment comment))

(defn delete [{:keys [name value ttl] :as row}]
  (require-arg "name" name)
  (require-arg "value" value)
  (require-arg "ttl" ttl)
  (change (merge row {:action "DELETE"})))

(defn get-change [account change-id]
  (r53-fn account
          {:method :get
           :url (str endpoint "/change/" change-id)}))

(defn change-id
  "Returns the change-id from a response or nil"
  [resp]
  (-> resp
      (zip/xml-zip)
      (zf-xml/xml1->
       :ChangeInfo
       :Id
       zf-xml/text)
      (#(re-find #"/change/(.*)" %))
      (get 1)))

(defn change-status [change-resource-record-set-response]
  (-> change-resource-record-set-response
      (clojure.zip/xml-zip)
      (zf-xml/xml1->
       :ChangeInfo
       :Status
       zf-xml/text)
      (.toLowerCase)
      (keyword)))

(defn *error? [resp]
  (= :tag :ErrorResponse))

(defn synced?
  "true if the given change-id has taken effect"
  [account change-id]
  (-> (get-change account change-id)
      :body
      (change-status)
      (= :insync)))

(defn parse-resource-record [rr]
  {:name (zf-xml/xml1-> rr :Name zf-xml/text)
   :type (zf-xml/xml1-> rr :Type zf-xml/text)
   :AliasTarget (zf-xml/xml1-> rr :AliasTarget zf-xml/text)
   :ttl (zf-xml/xml1-> rr :TTL zf-xml/text)

   :value (zf-xml/xml-> rr :ResourceRecords :ResourceRecord :Value zf-xml/text)})

(defn parse-resource-record-sets [body]
  (map parse-resource-record
       (-> body
           (zip/xml-zip)
           (zf-xml/xml->
            :ResourceRecordSets
            :ResourceRecordSet))))

(defn parse-resource-record-sets-response [body]
  {:rows (parse-resource-record-sets body)
   :truncated? (-> body (zip/xml-zip) (zf-xml/xml1-> :IsTruncated zf-xml/text) (read-string))})

(defn list-resource-record-sets
  "returns a map containing several keys
  :rows - a seq of maps, each map is a domain name record
  :truncated? - true if the result set is truncated"
  [account zone-id & {:keys [name type maxitems] :as query-params}]
  (let [resp (r53-fn account
                     {:method :get
                      :url (str endpoint "/hostedzone/" zone-id "/rrset")
                      :query-params query-params})]
    (when (= 200 (-> resp :status))
      (parse-resource-record-sets-response (-> resp :body)))))

(defn find
  "Filters the list of rows returned by list-resource-record-sets. match is a map. Returns all rows where all the values in match are = to the values in row.

  examples:
  (find-by-name credentials zone-id {:name \"foo.bar.com\"})
  (find-by-name credentials zone-id {:name \"foo.bar.com\" :type \"A\"}) "
  [credentials zone-id match]
  (filter #(submap? match %)
          (-> (list-resource-record-sets credentials zone-id) :rows)))

(defn block-until-sync [credentials change-id]
  (loop []
    (let [resp-body (-> (get-change credentials change-id)
                        :body)
          resp-status (change-status resp-body)]
      (if (= :pending resp-status)
        (do
          (Thread/sleep 5000)
          (recur))
        (if (= :insync resp-status)
          :insync
          resp-body)))))

(defn update-record
  "finds a single record using find-map. Deletes the original record, and creates a new record by merging merge-map into the old record."
  [credentials zone-id find-map merge-map]
  (let [old-rows (find credentials zone-id find-map)]
    (when (> (count old-rows) 1)
      (throw (Exception. (str "find should only return one row"))))
    (when (= (count old-rows) 0)
      (throw (Exception. (str "Couldn't find row to update"))))
    (let [old-row (first old-rows)
          resp (apply with-r53-transaction credentials zone-id
                      [(change (merge old-row {:action "DELETE"}))
                       (change (merge {:action "CREATE"} old-row merge-map))])]
      (println "update-record: resp=" resp)
      (if (= 200 (-> resp :status))
        (block-until-sync credentials (change-id (-> resp :body)))
        resp))))
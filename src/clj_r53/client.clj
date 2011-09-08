(ns clj-r53.client
  (:require [clojure.zip :as zip])
  (:require [clojure.contrib.zip-filter :as zf])
  (:require [clojure.contrib.zip-filter.xml :as zf-xml])
  (:use [clj-r53.core :only (endpoint r53-fn require-arg)]))

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

(defn change [{:keys [action type name ttl value]}]
  [:Change
   [:Action action]
   [:ResourceRecordSet
    [:Name name]
    [:Type type]
    [:TTL ttl]
    [:ResourceRecords
     [:ResourceRecord
      [:Value value]]]]])

(defn create-A-name
  "Returns the XML to create a new A name record. For use inside (with-r53-transaction)"
  [& {:keys [name ip ttl comment]}]
  (require-arg "name" name)
  (require-arg "ip" ip)
  (require-arg "ttl" ttl)
  (change {:action "CREATE"
           :type "A"
           :name name
           :ttl ttl
           :value ip}))

(defn delete-A-name
  "Returns the XML to create a new A name record. For use inside with-r53-transaction"
  [& {:keys [name ip ttl comment]}]
  (require-arg "name" name)
  (require-arg "ip" ip)
  (require-arg "ttl" ttl)
  (change {:action "DELETE"
           :type "A"
           :name name
           :ttl ttl
           :value ip}))

(defn create-CNAME
  [& {:keys [name ip ttl comment]}]
  (require-arg "name" name)
  (require-arg "ip" ip)
  (require-arg "ttl" ttl)
  (change {:action "CREATE"
           :type "CNAME"
           :name name
           :ttl ttl
           :value ip}))

(defn get-change [account change-id]
  (r53-fn account
          {:method :get
           :url (str endpoint "/change/" change-id)}))

(defn synced?
  "true if the given change-id has taken effect"
  [account change-id]
  (-> (get-change account change-id)
      :body
      (clojure.zip/xml-zip)
      (zf-xml/xml1->
       :ChangeInfo
       :Status
       zf-xml/text)
      (= "INSYNC")))

(defn parse-resource-record [rr]
  {:name (zf-xml/xml1-> rr :Name zf-xml/text)
   :type (zf-xml/xml1-> rr :Type zf-xml/text)
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
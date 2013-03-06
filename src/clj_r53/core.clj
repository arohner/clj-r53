(ns clj-r53.core
  (:import java.util.Date)
  (:import javax.crypto.Mac)
  (:import javax.crypto.spec.SecretKeySpec)
  (:import org.apache.http.impl.cookie.DateUtils)
  (:require [clj-http.client :as http])
  (:require [clojure.xml :as xml])
  (:require [clojure.java.io :as io])
  (:require [clojure.zip :as zip])
  (:require [clojure.data.zip.xml :as zf-xml])
  (:use [clojure.pprint :only (pprint)]
        [clojure.data.xml :only [emit-str sexp-as-element]]))

(def algo "HmacSHA1")

(defn http-date []
  (DateUtils/formatDate (Date.)))

(defn sign-string [key str]
  (let [key (SecretKeySpec. (.getBytes key) algo)]
    (->
     (doto (Mac/getInstance algo)
       (.init key)
       (.update (.getBytes str)))
     (.doFinal)
     (clj-http.util/base64-encode))))

(defn amzn-auth-header [account-info date]
  {"X-Amzn-Authorization"
   (format "AWS3-HTTPS AWSAccessKeyId=%s,Algorithm=%s,Signature=%s"
           (-> account-info :user)
           algo
           (sign-string (-> account-info :password) date))})

(def endpoint "https://route53.amazonaws.com/2011-05-05")

(defn r53-fn
  "Helper for r53. Makes a request, and parses the XML response. Args is a map, :headers will be merged into the request map, all other keys will be merged with clj-http/request. If args contains :body and it is a "
  [account args]
  (let [date (http-date)
        headers (merge (amzn-auth-header account date)
                       {"Date" date}
                       (:headers args))
        body (:body args)
        body (when body
               (if (vector? body)
                 (emit-str
                   (sexp-as-element
                    body))
                 body))
        request-map (merge 
                     {:throw-exceptions false
                      :headers headers
                      :body body}
                     (dissoc args :headers :body))
        response (http/request request-map)]
    (with-open [body-stream (-> response
                                  :body
                                  .getBytes
                                  io/input-stream)]
        (assoc-in response [:body] (clojure.xml/parse body-stream)))))

(defn create-hosted-zone-request [{:keys [name ref comment]}]
  [:CreateHostedZoneRequest {:xmlns "https://route53.amazonaws.com/doc/2011-05-05/"}
   [:Name name]
   [:CallerReference ref]
   (when comment
     [:HostedZoneConfig
      [:Comment comment]])])

(defn require-arg [name value]
  (when (not value)
    (throw (Exception. (format "%s is required" name)))))

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

(defn get-hosted-zone [account id]
  (let [date (http-date)]
    (r53-fn account
            {:method :get
             :url (str endpoint "/hostedzone/" id)})))

(defn get-date []
  "sanity check. Returns the date according to AWS"
  (get-in (http/get "https://route53.amazonaws.com/date")
          [:headers "date"]))

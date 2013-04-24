Introduction
============
A Clojure client for Amazon's route53 DNS service. 


Installation
===========
[clj-r53 "1.0.2"]


Account Ids
===========
Almost all fns take an argument called account-id. This is a map containing :user and :password. These are your AWS account id and secret. This is the same as your S3 credentials. To find it, go to aws.amazon.com, click on Account, click on Security Credentials. :user is the value in "Access Key ID" and :password is the value in "Secret Access Key".

Usage
=====
It's expected you've read the r53 documentation and are familiar with its concepts. 

Create a hosted DNS with
```clojure
(clj-r53.client/create-hosted-zone-request ...)
```

Get the zone-id with
```clojure
(clj-r53.client/list-hosted-zones)
```

r53-transaction
---------------
To make changes (add/delete records), use a with-r53-transaction block. It looks like

```clojure
(clj-r53.client/with-r53-transaction account-ids zone-id
   (client/create-A-name :name "foo.bar.com" :value "1.2.3.4" :ttl 300)
   (client/create-CNAME :name "baz.bar.com" :value "4.5.6.7" :ttl 300))
   ```

```clojure
  (let [account {:user (System/getenv "AWS_KEY")
                 :password (System/getenv "AWS_SECRET")}
        zone-id (get (clj-r53.client/list-hosted-zones account) "example.com.")]
    (with-r53-transaction account zone-id
      (create-A-name :name "www.example.com" :value "1.2.3.4" :ttl (str 600) :comment "comment")
      (create-A-name :name "www2.example.com" :value ["1.2.3.4" "2.3.4.5"] :ttl (str 600) :comment "comment")
      (create-CNAME  :name "foo.example.com" :value "foo2.example.com" :ttl (str 600) :comment "comment")
      (create-TXT    :name "smtpapi._domainkey.mail.example.com" :value "\"k=rsa; t=s; p=12345ABCDEF\"" :ttl (str 600) :comment "comment")
      (create-MX     :name "example.com" :value ["10 mail1.example.com" "20 mail2.example.com"] :ttl (str 600) :comment "comment")))

(let [account {:user (System/getenv "AWS_KEY")
               :password (System/getenv "AWS_SECRET")}
      zone-id (get (clj-r53.client/list-hosted-zones account) "example.com.")]
  (update-record account zone-id {:name "foo.example.com."} {:value "1.2.3.4"  :comment "my new comment"}))

   ```


Any number of actions can be included, subject to r53 limitations.

Viewing records
---------------
`(client/list-resource-record-sets)`

License
=======
EPL, same as Clojure

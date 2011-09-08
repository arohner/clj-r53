Introduction
============
A Clojure client for Amazon's route53 DNS service. 


Installation
===========
[clj-r53 "1.0.0"]


Account Ids
===========
Almost all fns take an argument called account-id. This is a map containing :user and :password. These are your AWS account id and secret. To view. go to aws.amazon.com, click on Account, click on Security Credentials. :user is the value in "Access Key ID" and :password is the value in "Secret Access Key".

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
(clj-r53.client/with-r53-transaction
   (client/create-A-name :name "foo.bar.com" :ip "1.2.3.4" :ttl 300)
   (client/create-CNAME :name "baz.bar.com" :ip "4.5.6.7" :ttl 300))
   ```

Any number of actions can be included, subject to r53 limitations.

Viewing records
---------------
`(client/list-resource-record-sets)`

License
=======
EPL, same as Clojure
(ns cashflow_api.handler-test
    (:use clojure.test
      cashflow_api.test-core
      ring.mock.request
      cashflow_api.handler)
    (:require [cheshire.core :as json]
      [cashflow_api.models.users :as u]
      [cashflow_api.auth :as auth]))

; WIll be rebound in test
(def ^{:dynamic true} *session-id* nil)

(defn with-session [t]
      (let [user (u/create {:name     "Some admin"
                            :email    "theadmin@example.com"
                            :password "sup3rs3cr3t"})
            session-id (auth/make-token! (:id user))]
           (with-bindings {#'*session-id* session-id}
                          (t))
           (u/delete-user user)))

(use-fixtures :each with-rollback)
(use-fixtures :once with-session)

(defn with-auth-header [req]
      (header req "Authorization" (str "Token " *session-id*)))

(deftest main-routes
         (testing "list users"
                  (let [response (app (with-auth-header (request :get "/users")))]
                       (is (= (:status response) 200))
                       (is (= (get-in response [:headers "Content-Type"]) "application/json; charset=utf-8"))))

         (testing "not-found route"
                  (let [response (app (request :get "/bogus-route"))]
                       (is (= (:status response) 404)))))

(deftest creating-user
         (testing "POST /users"
                  (let [user-count (u/count-users)
                        response (app (-> (request :post "/users")
                                          with-auth-header
                                          (body (json/generate-string {:name     "Joe Test"
                                                                       :email    "joe@example.com"
                                                                       :password "s3cret"}))
                                          (content-type "application/json")
                                          (header "Accept" "application/json")))]
                       (is (= (:status response) 201))
                       (is (substring? "/users/" (get-in response [:headers "Location"])))
                       (is (= (inc user-count) (u/count-users))))))

(deftest retrieve-user-stuff
         (let [user (u/create {:name "John Doe" :email "j.doe@mytest.com" :password "s3cret"})
               initial-count (u/count-users)]

              (testing "GET /users"
                       (doseq [i (range 4)]
                              (u/create {:name "Person" :email (str "person" i "@example.com") :password "s3cret"}))
                       (let [response (app (with-auth-header (request :get "/users")))
                             resp-data (json/parse-string (:body response))]
                            (is (= (:status response 200)))
                            ; A person's email contained in the response body
                            (is (substring? "person3@example.com" (:body response)))
                            ; All results present (including the user created in the let form)
                            (is (= (+ initial-count 4) (count (get resp-data "results" []))))
                            ; "count" field present
                            (is (= (+ initial-count 4) (get resp-data "count" [])))))

              (testing "GET /users/:id"
                       (let [response (app (with-auth-header (request :get (str "/users/" (:id user)))))]
                            (is (= (:body response) (json/generate-string user)))))))

(deftest deleting-user
         (let [user (u/create {:name "John Doe" :email "j.doe@mytest.com" :password "s3cr3t"})]

              (testing "DELETE /users/:id"
                       (let [response (app (with-auth-header (request :delete (str "/users/" (:id user)))))]
                            ; okay/no content status
                            (is (= (:status response) 204))
                            ; redirected to users index
                            (is (= "/users" (get-in response [:headers "Location"])))
                            ; user no longer exists in db
                            (is (nil? (u/find-by-id (:id user))))))))
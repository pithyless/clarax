(ns play-cljc.state.build
  (:require [play-cljc.state]
            [clara.rules.compiler :as compiler]
            [clara.macros :as macros]
            [clara.rules :as rules]
            [cljs.env :as env]
            [clara.rules.dsl :as dsl]
            [clojure.java.io :as io]
            [clojure.tools.reader :as r]
            [clojure.tools.reader.reader-types :refer [indexing-push-back-reader]]
            [clojure.spec.alpha :as s]
            [expound.alpha :as expound]
            [clojure.walk :as walk]))

(def ^:const ns-sym 'play-cljc.state)

(def ^:dynamic *cljs?* false)

(defn add-rule [name body]
  (let [rule (dsl/build-rule name body)]
    (when *cljs?*
      (swap! env/*compiler* assoc-in [:clara.macros/productions ns-sym name] rule))
    rule))

(defn add-query [name body]
  (let [query (dsl/build-query name body)]
    (when *cljs?*
      (swap! env/*compiler* assoc-in [:clara.macros/productions ns-sym name] query))
    query))

(defn transform-args [args]
  (->> args
       (map (juxt :key :val))
       (into {})))

(defn transform-insert-form [{:keys [record args]}]
  (list
    'play-cljc.state/insert!
    (list
      (symbol (str "map->" record))
      (update (transform-args args)
        :timestamp
        #(or % '(play-cljc.state/get-time))))))

(defn transform-delete-form [form]
  (list 'play-cljc.state/delete! form))

(defn transform-update-form [{:keys [record args]}]
  (list
    'play-cljc.state/update!
    record
    (transform-args args)))

(defn transform-upsert-form [query {:keys [record args update-args]}]
  (let [new-args (update (transform-args (concat args update-args))
                   :timestamp
                   #(or % '(play-cljc.state/get-time)))]
    (list
      'play-cljc.state/upsert!
      (list
        (symbol (str "map->" record))
        new-args)
      new-args
      query)))

(defn transform-execute-block [{:keys [forms]}]
  (mapcat
    (fn [code]
      ['_ code])
    forms))

(defn build-query [{:keys [record args]}]
  (vec (cons
         record
         (map (fn [{:keys [key val]}]
                (let [key (symbol (name key))]
                  (if (list? val)
                    (walk/prewalk-replace {'% key} val)
                    (list '= key val))))
           args))))

(defn transform-when-form [{:keys [binding] :as form}]
  (let [query (build-query form)]
    (if-let [{:keys [symbol arrow]} binding]
      (case arrow
        <<- [symbol '<- '(clara.rules.accumulators/distinct)
             :from query]
        <- (vec (concat [symbol '<-] query)))
      query)))

(defn select-form->query [query-name {:keys [binding] :as form}]
  (add-query query-name
    (list [] ['?ret '<-
              (case (:arrow binding)
                <<- '(clara.rules.accumulators/distinct)
                <- '(clara.rules.accumulators/max :timestamp :returns-fact true))
              :from (build-query form)])))

(defn transform-select-form [*session query {:keys [binding] :as form}]
  (list
    (:symbol binding)
    (list
      (list 'comp :?ret 'first)
      (list
        'some->
        (list 'deref *session)
        (list 'clara.rules/query query)))))

(defn add-rules [*session rules]
  (when *cljs?*
    (swap! env/*compiler* assoc-in [:clara.macros/productions ns-sym] {}))
  (flatten
    (for [i (range (count rules))
          :let [{:keys [left right]} (get rules i)
                sym (symbol (str "rule-" i))
                left-side (mapv transform-when-form (:forms left))
                selects (->> right
                             (keep (fn [[type block]]
                                     (when (= type :select) block)))
                             (mapcat :forms)
                             vec)
                upserts (->> right
                             (keep (fn [[type block]]
                                     (when (= type :upsert) block)))
                             (mapcat :forms)
                             vec)
                queries (reduce-kv
                          (fn [v i form]
                            (conj v (select-form->query (str sym "-query-" i) form)))
                          []
                          (into selects upserts))
                [select-queries upsert-queries] (split-at (count selects) queries)
                execute-blocks (->> right
                                    (keep (fn [[type block]]
                                            (when (= type :execute) block)))
                                    vec)
                bindings (concat
                           (mapcat (partial transform-select-form *session) select-queries selects)
                           (mapcat transform-execute-block execute-blocks))
                right-side (mapcat
                             (fn [[type block]]
                               (case type
                                 :select []
                                 :insert (map transform-insert-form (:forms block))
                                 :delete (map transform-delete-form (:forms block))
                                 :update (map transform-update-form (:forms block))
                                 :upsert (map transform-upsert-form upsert-queries (:forms block))
                                 :execute []))
                             right)]]
      (cons (add-rule sym (concat left-side ['=>]
                            (list
                              (concat
                                (list 'let (vec bindings))
                                right-side))))
        queries))))

(s/def ::binding (s/cat
                   :symbol symbol?
                   :arrow '#{<- <<-}))
(s/def ::pair (s/cat
                :key keyword?
                :val any?))

(s/def ::when-form (s/cat
                     :binding (s/? ::binding)
                     :record symbol?
                     :args (s/* ::pair)))
(s/def ::select-form (s/cat
                       :binding ::binding
                       :record symbol?
                       :args (s/* ::pair)))
(s/def ::insert-form (s/cat
                       :record symbol?
                       :args (s/* ::pair)))
(s/def ::delete-form #(not (keyword? %)))
(s/def ::update-form (s/cat
                       :record any?
                       :args (s/* ::pair)))
(s/def ::upsert-form (s/cat
                       :record symbol?
                       :args (s/* ::pair)
                       :binding (s/cat :arrow '#{<-})
                       :update-args (s/* ::pair)))

(s/def ::when-block (s/cat
                      :header #{:when}
                      :forms (s/+ (s/spec ::when-form))))
(s/def ::select-block (s/cat
                        :header #{:select}
                        :forms (s/+ (s/spec ::select-form))))
(s/def ::insert-block (s/cat
                        :header #{:insert}
                        :forms (s/+ (s/spec ::insert-form))))
(s/def ::delete-block (s/cat
                        :header #{:delete}
                        :forms (s/+ (s/spec ::delete-form))))
(s/def ::update-block (s/cat
                        :header #{:update}
                        :forms (s/+ (s/spec ::update-form))))
(s/def ::upsert-block (s/cat
                        :header #{:upsert}
                        :forms (s/+ (s/spec ::upsert-form))))
(s/def ::execute-block (s/cat
                         :header #{:execute}
                         :forms (s/+ list?)))

(s/def ::rule (s/cat
                :left ::when-block
                :right (s/* (s/alt
                              :select ::select-block
                              :insert ::insert-block
                              :delete ::delete-block
                              :update ::update-block
                              :upsert ::upsert-block
                              :execute ::execute-block))))
(s/def ::file (s/cat
                :init-forms (s/* (s/spec ::insert-form))
                :rules (s/* ::rule)))

(defn parse [spec content]
  (let [res (s/conform spec content)]
    (if (= ::s/invalid res)
      (throw (ex-info (expound/expound-str spec content) {}))
      res)))

(defmacro init-cljs [*session & forms]
  (binding [*cljs?* true]
    (let [parsed-forms (parse ::file forms)
          init-forms (->> parsed-forms
                          :init-forms
                          (mapv transform-insert-form))
          _ (->> parsed-forms
                 :rules
                 (add-rules *session)
                 vec)
          session (macros/sources-and-options->session-assembly-form [(list 'quote ns-sym)])]
      `(->> ~session ~@init-forms rules/fire-rules (reset! ~*session)))))

(extend-type java.util.Map
  compiler/IRuleSource
  (load-rules [m]
    [m]))

(defmacro init-clj [*session & forms]
  (let [parsed-forms (parse ::file forms)
        init-forms (->> parsed-forms
                        :init-forms
                        (mapv transform-insert-form))
        rules (->> parsed-forms
                   :rules
                   (add-rules *session)
                   vec)]
    `(->> ~rules compiler/mk-session ~@init-forms rules/fire-rules (reset! ~*session))))

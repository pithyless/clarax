(ns pixel-midi-gogo.core
  (:require [clara.macros :as macros]
            [clara.rules :as rules]
            [cljs.env :as env]
            [clara.rules.dsl :as dsl]
            [clojure.java.io :as io]
            [clojure.tools.reader :as r]
            [clojure.tools.reader.reader-types :refer [indexing-push-back-reader]]
            [clojure.spec.alpha :as s]))

(def ^:const ns-sym 'pixel-midi-gogo.core)

(defn add-rule [name body]
  (->> (dsl/build-rule name body)
       (swap! env/*compiler* assoc-in [:clara.macros/productions ns-sym name])))

(defn transform-insert-form [{:keys [record args]}]
  (list
    'pixel-midi-gogo.core/insert
    (list
      (symbol (str "map->" record))
      (update
        (->> args
             (map (juxt :key :val))
             (into {}))
        :timestamp
        #(or % '(.getTime (js/Date.)))))))

(defn transform-select-form [{:keys [binding record expressions args]}]
  (let [query (vec (concat
                     [record]
                     expressions
                     (map (fn [{:keys [key val]}]
                            (list '= (symbol (name key)) val))
                       args)))]
    (if-let [{:keys [symbol arrow accumulator]} binding]
      (if accumulator
        [symbol arrow accumulator :from query]
        (into [symbol arrow] query))
      query)))

(defn add-rules [rules]
  (swap! env/*compiler* assoc-in [:clara.macros/productions ns-sym] {})
  (doseq [i (range (count rules))
          :let [{:keys [select insert]} (get rules i)
                sym (symbol (str "rule-" i))
                left-side (mapv transform-select-form (:forms select))
                right-side (map (fn [[type value]]
                                  (if (= type :insert)
                                    (transform-insert-form value)
                                    value))
                             (:forms insert))]]
    (add-rule sym (concat left-side ['=>]
                    (if (empty? right-side) ['(do)] right-side)))))

(defn read-file [filename]
  (let [reader (indexing-push-back-reader (slurp (io/reader filename)))]
    (loop [forms []]
      (if-let [form (r/read {:eof nil} reader)]
        (recur (conj forms form))
        forms))))

(s/def ::binding (s/cat
                   :symbol symbol?
                   :arrow #{'<-}
                   :accumulator (s/? list?)))
(s/def ::pair (s/cat
                :key keyword?
                :val any?))

(s/def ::select-form (s/cat
                       :binding (s/? ::binding)
                       :record symbol?
                       :expressions (s/* list?)
                       :args (s/* ::pair)))
(s/def ::insert-form (s/cat
                       :record symbol?
                       :args (s/* ::pair)))

(s/def ::select-block (s/cat
                        :header #{:select}
                        :forms (s/+ (s/spec ::select-form))))
(s/def ::insert-block (s/cat
                        :header #{:insert}
                        :forms (s/+ (s/spec (s/or
                                              :execute list?
                                              :insert ::insert-form)))))

(s/def ::rule (s/cat
                :select ::select-block
                :insert (s/? ::insert-block)))
(s/def ::file (s/cat
                :init-forms (s/* (s/spec ::insert-form))
                :rules (s/* ::rule)))

(defmacro init [nses files]
  (let [parsed-files (mapv #(s/conform ::file (read-file %))
                       files)
        init-forms (->> parsed-files
                        (mapcat :init-forms)
                        (mapv transform-insert-form))
        rules (->> parsed-files
                   (mapcat :rules)
                   vec)
        _ (add-rules rules)
        session (macros/sources-and-options->session-assembly-form
                  (map #(list 'quote %) nses))]
    `(do
       (pixel-midi-gogo.core/watch-files ~files)
       (reset! pixel-midi-gogo.core/*session
         (-> ~session ~@init-forms rules/fire-rules)))))


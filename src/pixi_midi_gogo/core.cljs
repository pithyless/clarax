(ns pixi-midi-gogo.core
  (:require [clara.rules :refer [insert fire-rules]]
            [rum.core :as rum])
  (:require-macros [clara.rules :refer [defsession defrule]]
                   [pixi-midi-gogo.core :refer [read-rules]]))

(defrecord Person [name email])
(defrecord Element [value parent])

(rum/defc empty-comp
  [content]
  content)

(defrule elem
  [?elem <- Element]
  =>
  (rum/mount
    (empty-comp (:value ?elem))
    (.querySelector js/document (:parent ?elem))))

(fire-rules
  (read-rules
    {:in pixi-midi-gogo.core
     :on [?person <- Person]
     :do (js/console.log (pr-str ?person))}
    {:in pixi-midi-gogo.core
     :on [Person (= "Alice" name) (= ?email email)]
     :do (js/console.log ?email)}
    {:in pixi-midi-gogo.core
     :do (insert (->Person "Alice" "alice@sekao.net"))}
    {:in pixi-midi-gogo.core
     :do (insert (->Person "Bob" "bob@sekao.net"))}
    {:in pixi-midi-gogo.core
     :do (insert (->Element [:h1 "Hello, world!"] "#app"))}))


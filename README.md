[![Clojars Project](https://img.shields.io/clojars/v/net.sekao/clarax.svg)](https://clojars.org/net.sekao/clarax)

## Introduction

A library providing an alternative syntax for [Clara Rules](https://github.com/cerner/clara-rules). After deep contemplation, thorough discussion with the greatest minds I know, and summoning the willpower of a Greek god, I decided to resist calling it O'Doyle Rules. You are welcome.

Why does clara need a new coat of paint? Consider an example using clara's current syntax:

```clojure
(ns examples.clara
  (:require [clara.rules :as clara #?(:clj :refer :cljs :refer-macros) [defsession defquery defrule]]
            [clara.rules.accumulators :as acc]))

(defrecord Player [x y health])
(defrecord Enemy [x y health])

(defrule remove-dead-enemies
  [?enemy <- Enemy (= health 0)]
  =>
  (clara/retract! ?enemy))

(defquery get-player
  []
  [?player <- Player])

(defquery get-nearby-enemies
  []
  [?player <- Player]
  [?enemy <- (acc/all) :from [Enemy (= (:x ?player) x) (= (:y ?player) y)]])

(defquery get-enemies-at
  [:?x :?y]
  [?enemy <- (acc/all) :from [Enemy (= ?x x) (= ?y y)]])

(defsession session 'examples.clara)

(def *session
  (-> session
      (clara/insert (->Player 1 1 10)
                    (->Enemy 1 1 10)
                    (->Enemy 1 1 10)
                    (->Enemy 2 2 10)
                    (->Enemy 2 2 0))
      clara/fire-rules
      atom))

(-> (clara/query @*session get-player)
    first :?player println)
;; => #examples.clara.Player{:x 1, :y 1, :health 10}

(-> (clara/query @*session get-nearby-enemies)
    first :?enemy println)
;; => [#examples.clara.Enemy{:x 1, :y 1, :health 10} #examples.clara.Enemy{:x 1, :y 1, :health 10}]

(-> (clara/query @*session get-enemies-at :?x 2 :?y 2)
    first :?enemy println)
;; => [#examples.clara.Enemy{:x 2, :y 2, :health 10}]
;; this only returns one, because the other enemy at (2, 2) was removed by the remove-dead-enemies rule
```

I see two main problems:

1. The syntax for defining rules and queries is a bit weird and hard to read. The whole `<-` thingy for binding local names, the `:from [...]` syntax for accumulators, and so on. I think we can make this more intuitive.
2. We are forced to make an icky global var for every rule and query we define. Generally it's better for a macro to just return a value and let the user `def` it themselves if they want to.

Here's the same program using clarax:

```clojure
(ns examples.clarax
  (:require [clara.rules :as clara]
            [clara.rules.accumulators :as acc]
            #?(:clj  [clarax.macros-java :refer [->session]]
               :cljs [clarax.macros-js :refer-macros [->session]])))

(defrecord Player [x y health])
(defrecord Enemy [x y health])

(def *session
  (-> ;; all rules/queries are specified in a single hash map.
      ;; as you can see, rules look like familiar `let` expressions,
      ;; mapping a name (enemy) to a record type (Enemy).
      ;; any binding pair can be followed by a :when expression,
      ;; similar to the :when expressions in `for`, `doseq`, etc.
      {:remove-dead-enemies
       (let [enemy Enemy
             :when (= (:health enemy) 0)]
         ;; the body of the `let` is what will run when the rule fires
         (clara/retract! enemy))

       ;; queries look exactly like rules, except the are surrounded by `fn`
       :get-player
       (fn []
         (let [player Player]
           ;; the body of the `let` determines what the query will return.
           ;; in this case, it will simply return the entire player record.
           player))

       :get-nearby-enemies
       (fn []
         (let [player Player
               enemy [Enemy (acc/all)] ;; this is how you use an accumulator
               :when (and (= (:x player) (:x enemy))
                          (= (:y player) (:y enemy)))]
           enemy))

       :get-enemies-at
       (fn [?x ?y]
         (let [{:keys [x y] :as enemy} [Enemy (acc/all)] ;; you can destructure just like in a normal `let` form
               :when (and (= ?x x) (= ?y y))]
           enemy))}
      ;; this macro creates the session from the hash map
      ->session
      ;; you use the same functions from clara to insert and fire rules
      (clara/insert (->Player 1 1 10)
                    (->Enemy 1 1 10)
                    (->Enemy 1 1 10)
                    (->Enemy 2 2 10)
                    (->Enemy 2 2 0))
      clara/fire-rules
      atom))

(println (clara/query @*session :get-player))
;; => #examples.clara.Player{:x 1, :y 1, :health 10}

(println (clara/query @*session :get-nearby-enemies))
;; => [#examples.clara.Enemy{:x 1, :y 1, :health 10} #examples.clara.Enemy{:x 1, :y 1, :health 10}]

(println (clara/query @*session :get-enemies-at :?x 2 :?y 2))
;; => [#examples.clara.Enemy{:x 2, :y 2, :health 10}]
;; this only returns one, because the other enemy at (2, 2) was removed by the remove-dead-enemies rule
```

## Usage

You can include this library in your project dependencies using the version number in the badge above.

## Development

* Install [the Clojure CLI tool](https://clojure.org/guides/getting_started#_clojure_installer_and_cli_tools)
* To run the examples in this README: `clj -A:dev`
* To run the tests: `clj -A:test`
* To install the release version: `clj -A:prod install`

## Licensing

All files that originate from this project are dedicated to the public domain. I would love pull requests, and will assume that they are also dedicated to the public domain.

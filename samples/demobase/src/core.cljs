;; Heavily based on GlkOte by Andrew Plotkin <erkyrath@eblong.com>.
;; (http://eblong.com/zarf/glk/glkote.html)

(ns ^{:doc "Equivalent of http://eblong.com/zarf/glk/glkote/sample-demobase.html"}
  samples.demobase.core
  (:require-macros [cljs.core.async.macros :refer [go-loop]])
  (:require [omkote.core :as omkote :refer [game-component]]
            [om.core :as om]
            [cljs.core.async :refer [<! >! chan]]
            [clojure.string :as string]))

(enable-console-print!)

(def initial-state
  {:windows [{:id 1
              :type :buffer
              :style {:top "0px" :height "100%" :width "100%"}
              :blocks []
              :input {:type :line :maxlen 80 :initial "" :terminators [] :hyperlink false}}]})

(defn say
  "Turn simple text into message for game engine."
  ([v] (say v "normal" false))
  ([v style] (say v style false))
  ([v style runon]
     (let [[fst & rst] (drop-while string/blank? (string/split v #"\n"))]
       (cons
        {:content [style fst] :append runon}
        (map (fn [line] (if (string/blank? line) {} {:content [style line]})) rst)))))

(defn say-runon [v style]
  (say v style true))

(defn startup []
  [(say "This is an  demo of an IF app written in ClojureScript. You could use it as a starting point for your own IF project (or any web page that wants to accept command-line input and print responses!)

Just customize the startup() function to print your initial text, and customize the respond() function to print a response based on its argument (the player's input). But for now it's simply an experiment!.
")
   (say "Welcome to...")
   (say "The Game" "header")])

(defn respond [v]
  (say (str "You typed " v ".")))

(defn game-select
  "Format update message for the game."
  [& content]
  (let [flattened (flatten content)]
    {:type :update
     :windows nil
     :content [{:id 1 :text flattened}]
     :input nil}))

(def game (atom initial-state))

(let [event-channel (chan)
      control-channel (chan)
      prompt ">"]

  (swap! game assoc :event-channel event-channel :control-channel control-channel)

  ;; our game implementation:
  (go-loop []
    (when-let [{:keys [type value] :as event} (<! event-channel)]
      (>! control-channel
          (case type
            :init (game-select (startup) (say prompt))
            :line (game-select (say-runon value "input") (respond value) (say prompt))
            :arrange nil))
      (recur)))

  ;; install the component
  (om/root game-component game {:target (.getElementById js/document "gameport")}))



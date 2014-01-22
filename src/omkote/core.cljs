(ns ^{:doc "A simple OM/core.async implementation of an IF window interface a la GlkOte."}
  omkote.core
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [cljs.core.async :refer [put! <! >! chan mix admix unmix]]))

(enable-console-print!)

(def ENTER-KEY 13)

;;==============================================================================
;; components - buffer window

(defn handle-keydown
  "Handle input event in buffer window and send down the window's input channel."
  [e {:keys [user-input window-id] :as state} owner]
  (when (== (.-which e) ENTER-KEY)
    (when-let [line (.-value (om/get-node owner "user-text"))]
      (put! user-input {:window-id window-id :text line})
      (set! (.-value (om/get-node owner "user-text")) ""))))

(defn map->js [m]
  (apply js-obj (apply concat (map (fn [[k v]] [(str (name k)) v]) (seq m)))))

(defn buffer-window-state
  "Construct initial state for a buffer window."
  [win-id & {:keys [style] :as opts :or {style {:top "0px"
                                                :left "0px"
                                                :height "200px"
                                                :width "100%"}}}]
  (merge {:type :buffer
          :blocks []
          :user-input (chan)
          :ui-updates (chan)
          :window-id win-id
          :style style}
         opts))

(defn buffer-window
  "Construct a buffer-window component. Renders the accumulated state
to divs, sending user input to a channel and contains a go loop to
respond to incoming requests from the game."
  
  [bw-state owner opts]

  (reify

    ;; Set up loop to apply any incoming text to the window
    om/IWillMount
    (will-mount [_]
      (let [ui-updates (:ui-updates bw-state)
            window-id (:window-id bw-state)]
        (go
         (while true
           (let [{:keys [type line] :as update} (<! ui-updates)]
             (println "Applying update " update " in window " window-id)
             (cond
              (= type :append) (om/transact! bw-state :blocks conj {:text line})))))))

    ;; Render all paragraphs and the input
    om/IRender
    (render [_]
      (dom/div #js {:className "buffer-window window" :id (:window-id bw-state) :style (map->js (:style bw-state))}
               (apply dom/div #js {:className "content"}
                      (map 
                       (fn [block idx]
                         (dom/p #js {:key idx}
                                (:text block)))
                       (:blocks bw-state)
                       (range)))
               (dom/input #js {:type "text"
                               :ref "user-text"
                               :onKeyDown (om/bind handle-keydown bw-state owner)})))))

;;==============================================================================
;; grid window

(defn grid-window
  "Construct a grid-window component."

  [gw-state owner opts]
  (reify

    om/IRender
    (render [_]
      (dom/div #js {:className "grid-window window"}
               (apply dom/div #js {:className "content"}
                      (map 
                       (fn [block idx]
                         (dom/p #js {:key idx}
                                (:text block)))
                       (:blocks gw-state)
                       (range)))))))


;;==============================================================================
;; gameport

(defn window-component
  "Forward to buffer or grid window as appropriate."
  [state owner opts]
  (case (:type state)
    :buffer (buffer-window state owner opts)
    :grid (buffer-window state owner opts)))

(defn game-state
  "Create a game state from initial windows - each window's input channel will be
routed into the game's overarching input channel."
  [& window-states]
  {:windows (vec window-states) ; om cursor require indexed types -
                                        ; not mere sequences!

   ;; input channel combines inputs from all windows
   :input-channel (let [c (chan)
                        m (mix c)]
                    (doseq [window-input-channel (map :user-input window-states)]
                      (admix m window-input-channel))
                    c)

   ;; control channel forwards to relevant window
   :control-channel (let [c (chan)]
                      (go
                       (loop []
                         (println "In control channel loop")
                         ;; route to target window
                         (when-let [{:keys [window-id] :as msg} (<! c)]
                           (println "Received msg on control channel: " msg)
                           (let [target-window (first (filter #(= (:window-id %) window-id) window-states))]
                             (println "Routing msg: " msg " to target window " window-id)
                             (>! (:ui-updates target-window) msg)
                             (recur)))))
                      c)
   })

(defn game-component 
  "Builds a responsive game into the specified gameport element."
  [{:keys [windows] :as game} owner opts]
  (reify
    om/IRender 
    (render [_]
      (dom/div #js {:className "window-port"}
               (om/build-all window-component windows)))))

;;==============================================================================
;; Usage: wire it up and run

;; create game state
(let [game (atom (game-state (buffer-window-state "bw1")
                             (buffer-window-state "bw2" :style {:top "200px"})))
      input (:input-channel @game)
      control (:control-channel @game)]
  
  ;; our game implementation:
  (go
   (loop []
     (let [{:keys [window-id text]} (<! input)]
       (println "Received user input: " text " from window " window-id)
       (println "Sending to control channel")
       (>! control {:window-id window-id :type :append :line text})
       (recur))))

  (om/root game game-component (.getElementById js/document "gameport")))

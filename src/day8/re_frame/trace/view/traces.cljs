(ns day8.re-frame.trace.view.traces
  (:require [day8.re-frame.trace.view.components :as components]
            [day8.re-frame.trace.utils.pretty-print-condensed :as pp]
            [re-frame.trace :as trace]
            [clojure.string :as str]
            [day8.re-frame.trace.utils.localstorage :as localstorage]
            [cljs.pprint :as pprint]
            [clojure.set :as set]
            [mranderson047.reagent.v0v6v0.reagent.core :as r]
            [mranderson047.re-frame.v0v10v2.re-frame.core :as rf]
            [day8.re-frame.trace.utils.re-com :as rc]))

(defn query->fn [query]
  (if (= :contains (:filter-type query))
    (fn [trace]
      (str/includes? (str/lower-case (str (:operation trace) " " (:op-type trace)))
                     (:query query)))
    (fn [trace]
      (< (:query query) (:duration trace)))))

(defn add-filter [filter-items filter-input filter-type]
  (rf/dispatch [:traces/add-filter filter-input filter-type]))

(defn render-traces [visible-traces filter-items filter-input trace-detail-expansions]
  (doall
    (->>
      visible-traces
      (map-indexed (fn [index {:keys [op-type id operation tags duration] :as trace}]
                     (let [show-row? (get-in @trace-detail-expansions [:overrides id]
                                             (:show-all? @trace-detail-expansions))
                           op-name   (if (vector? operation)
                                       (second operation)
                                       operation)
                           #_#__ (js/console.log (devtools/header-api-call tags))]
                       (list [:tr {:key      id
                                   :on-click #(rf/dispatch [:traces/toggle-trace id])
                                   :class    (str/join " " ["trace--trace"
                                                            (case op-type
                                                              :sub/create "trace--sub-create"
                                                              :sub/run "trace--sub-run"
                                                              :sub/dispose "trace--sub-run"
                                                              :event "trace--event"
                                                              :render "trace--render"
                                                              :re-frame.router/fsm-trigger "trace--fsm-trigger"
                                                              nil)])}

                              [:td.trace--toggle
                               [:button.expansion-button (if show-row? "▼" "▶")]]
                              [:td.trace--op
                               [:span.op-string {:on-click (fn [ev]
                                                             (add-filter filter-items (name op-type) :contains)
                                                             (.stopPropagation ev))}
                                (str op-type)]]
                              [:td.trace--op-string
                               [:span.op-string {:on-click (fn [ev]
                                                             (add-filter filter-items (name op-name) :contains)
                                                             (.stopPropagation ev))}
                                (pp/truncate 20 :middle (pp/str->namespaced-sym op-name)) " "
                                [:span
                                 {:style {:opacity 0.5
                                          :display "inline-block"}}
                                 (when-let [[_ & params] (or (get tags :query-v)
                                                             (get tags :event))]
                                   (->> (map pp/pretty-condensed params)
                                        (str/join ", ")
                                        (pp/truncate-string :middle 40)))]]]
                              [:td.trace--meta
                               id
                               #_ #_(.toFixed duration 1) " ms"]]
                             (when show-row?
                               [:tr.trace--details {:key       (str id "-details")
                                                    :tab-index 0}
                                [:td]
                                [:td.trace--details-tags {:col-span 2
                                                          :on-click #(.log js/console tags)}
                                 [:div.trace--details-tags-text
                                  (let [tag-str           (with-out-str (pprint/pprint tags))
                                        string-size-limit 400]
                                    (if (< string-size-limit (count tag-str))
                                      (str (subs tag-str 0 string-size-limit) " ...")
                                      tag-str))]]
                                [:td.trace--meta.trace--details-icon
                                 {:on-click #(.log js/console tags)}]]))))))))

(defn render [traces]
  (let [filter-input            (r/atom "")
        filter-items            (rf/subscribe [:traces/filter-items])
        filter-type             (r/atom :contains)
        input-error             (r/atom false)
        categories              (rf/subscribe [:traces/categories])
        trace-detail-expansions (rf/subscribe [:traces/expansions])
        beginning               (rf/subscribe [:epochs/beginning-trace-id])
        end                     (rf/subscribe [:epochs/ending-trace-id])
        current-traces          (rf/subscribe [:traces/current-event-traces])
        show-epoch-traces?      (rf/subscribe [:traces/show-epoch-traces?])]
    (fn []
      (let [toggle-category-fn #(rf/dispatch [:traces/toggle-categories %])
            traces-to-filter   (if @show-epoch-traces?
                                 @current-traces
                                 @traces)
            visible-traces     (cond->> traces-to-filter
                                        ;; Remove cached subscriptions. Could add this back in as a setting later
                                        ;; but it's pretty low signal/noise 99% of the time.
                                        true (remove (fn [trace] (and (= :sub/create (:op-type trace))
                                                                      (get-in trace [:tags :cached?]))))
                                        (seq @categories) (filter (fn [trace] (when (contains? @categories (:op-type trace)) trace)))
                                        (seq @filter-items) (filter (apply every-pred (map query->fn @filter-items)))
                                        true (sort-by :id))
            save-query         (fn [_]
                                 (if (and (= @filter-type :slower-than)
                                          (js/isNaN (js/parseFloat @filter-input)))
                                   (reset! input-error true)
                                   (do
                                     (reset! input-error false)
                                     (add-filter filter-items @filter-input @filter-type))))]

        [:div.tab-contents
         [:div.filter
          [:div.filter-control
           [:ul.filter-categories "show: "
            [:li.filter-category {:class    (when (contains? @categories :event) "active")
                                  :on-click #(rf/dispatch [:traces/toggle-categories #{:event}])}
             "events"]
            [:li.filter-category {:class    (when (contains? @categories :sub/run) "active")
                                  :on-click #(rf/dispatch [:traces/toggle-categories #{:sub/run :sub/create :sub/dispose}])}
             "subscriptions"]
            [:li.filter-category {:class    (when (contains? @categories :render) "active")
                                  :on-click #(rf/dispatch [:traces/toggle-categories #{:render}])}
             "reagent"]
            [:li.filter-category {:class    (when (contains? @categories :re-frame.router/fsm-trigger) "active")
                                  :on-click #(rf/dispatch [:traces/toggle-categories #{:re-frame.router/fsm-trigger :componentWillUnmount}])}
             "internals"]]
           [rc/checkbox
            :model show-epoch-traces?
            :on-change #(rf/dispatch [:traces/update-show-epoch-traces? %])
            :label "Show only traces for this epoch?"]
           [:div.filter-fields
            [:select {:value     @filter-type
                      :on-change #(reset! filter-type (keyword (.. % -target -value)))}
             [:option {:value "contains"} "contains"]
             [:option {:value "slower-than"} "slower than"]]
            [:div.filter-control-input {:style {:margin-left 10}}
             [components/search-input {:on-save   save-query
                                       :on-change #(reset! filter-input (.. % -target -value))}]
             (if @input-error
               [:div.input-error {:style {:color "red" :margin-top 5}}
                "Please enter a valid number."])]]]
          [:ul.filter-items
           (map (fn [item]
                  ^{:key (:id item)}
                  [:li.filter-item
                   [:button.button
                    {:style    {:margin 0}
                     :on-click #(rf/dispatch [:traces/remove-filter (:id item)])}
                    (:filter-type item) ": " [:span.filter-item-string (:query item)]]])
                @filter-items)]]
         [:pre @beginning " to " @end]
         [components/autoscroll-list {:class "panel-content-scrollable" :scroll? true}
          [:table
           [:thead>tr
            [:th {:style {:padding 0}}
             [:button.text-button
              {:style    {:cursor "pointer"}
               :on-click #(rf/dispatch [:traces/toggle-all-expansions])}
              (if (:show-all? @trace-detail-expansions) "-" "+")]]
            [:th "operations"]
            [:th
             [:button {:class    (str/join " " ["filter-items-count"
                                                (when (pos? (count @filter-items)) "active")])
                       :on-click #(rf/dispatch [:traces/reset-filter-items])}
              (when (pos? (count @filter-items))
                (str (count visible-traces) " of "))
              (str (count @current-traces))]
             " traces "
             (when (pos? (count @current-traces))
               [:span "(" [:button.text-button {:on-click #(do (trace/reset-tracing!) (reset! current-traces []))} "clear"] ")"])]
            [:th {:style {:text-align "right"}} "meta"]]
           [:tbody (render-traces visible-traces filter-items filter-input trace-detail-expansions)]]]]))))



#!/usr/bin/env bb

(ns agenty
  (:require
    [babashka.fs :as fs]
    [babashka.http-client :as http]
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.edn :as edn]))

;;; === Config ===

(def config-file (str (System/getProperty "user.home") "/.agenty-config.edn"))
(def agents-dir (str (System/getProperty "user.home") "/.agenty-agents"))

(defn load-config []
  (if (fs/exists? config-file)
    (edn/read-string (slurp config-file))
    {}))

(def config (load-config))

;;; === State ===

(defonce ui-mode (atom :normal))
(defonce current-provider (atom :openrouter))
(defonce current-model (atom :claude-3-5-sonnet))
(defonce state (atom {:active :main :history [] :agents #{:main}}))

;;; === API Config ===

(def openrouter-key (or (System/getenv "OPENROUTER_API_KEY")
                        (:openrouter-api-key config)))

(def together-key (or (System/getenv "TOGETHER_API_KEY") (:together-api-key config)))

(def providers
  {:openrouter {:key openrouter-key
                :url "https://openrouter.ai/api/v1/chat/completions"
                :models {:claude-3-5-sonnet "anthropic/claude-3.5-sonnet"
                        :claude-3-opus "anthropic/claude-3-opus"
                        :gpt-4o "openai/gpt-4o"}}
   :together {:key together-key
              :url "https://api.together.xyz/v1/chat/completions"
              :models {:llama-2-13b "togethercomputer/llama-2-13b-chat"}}})

;;; === UI ===

(defn ui-print [& args]
  (when (= @ui-mode :normal) (apply println args)))

(defn ui-print-always [& args]
  (apply println args))

(defn quiet-response [content]
  (if (= @ui-mode :quiet) content (str "🤖: " content)))

;;; === Storage ===

(defn sanitize-agent-id [s]
  (-> s str/lower-case (str/replace #"[^a-z0-9_-]" "_") keyword))

(defn agent-file [agent-id]
  (str agents-dir "/" (name agent-id) ".edn"))

(defn load-agent-history [agent-id]
  (try
    (if (fs/exists? (agent-file agent-id))
      (edn/read-string (slurp (agent-file agent-id)))
      [])
    (catch Exception e
      (ui-print "⚠️ Failed to load agent history:" (.getMessage e))
      [])))

(defn save-agent-history! [agent-id history]
  (try
    (fs/create-dirs agents-dir)
    (spit (agent-file agent-id) (pr-str history))
    true
    (catch Exception e
      (ui-print "⚠️ Failed to save agent history:" (.getMessage e))
      false)))

(defn sync-state-to-disk! []
  (let [active (:active @state)
        hist (:history @state)]
    (save-agent-history! active hist)))

;;; === API ===

(defn get-current-config []
  (let [provider @current-provider
        model-key @current-model
        provider-config (get providers provider)
        model-name (get-in provider-config [:models model-key])]
    {:provider provider :model-key model-key :model-name model-name
     :url (:url provider-config) :api-key (:key provider-config)}))

(defn call-llm [messages]
  (let [{:keys [provider model-name url api-key]} (get-current-config)]
    (try
      (when-not api-key
        (ui-print "⚠️ No API key for" provider ". Set OPENROUTER_API_KEY or TOGETHER_API_KEY env var.")
        (throw (ex-info "No API key available" {:provider provider})))
      
      (let [payload {:model model-name :messages messages :temperature 0.7}
            resp (http/post url
                            {:headers {"Authorization" (str "Bearer " api-key)
                                       "Content-Type" "application/json"}
                             :body (json/generate-string payload)})
            body (json/parse-string (:body resp) true)]
        (if (= 200 (:status resp))
          (get-in body [:choices 0 :message :content])
          (throw (ex-info "API error" {:status (:status resp) :body body}))))
      (catch Exception e
        (ui-print "❌ LLM call failed for" provider ":" (.getMessage e))
        (when (and (= provider :openrouter) together-key)
          (ui-print "🔄 Falling back to Together AI...")
          (reset! current-provider :together)
          (reset! current-model :llama-2-13b)
          (call-llm messages))))))

;;; === Agent Management ===

(defn list-agents []
  (if (= @ui-mode :quiet)
    (doseq [a (sort (:agents @state))] (println (name a)))
    (do (ui-print "📂 Agents:")
        (doseq [a (sort (:agents @state))] (ui-print " -" (name a))))))

(defn switch-agent! [agent-id]
  (let [agent-id (sanitize-agent-id agent-id)]
    (if (contains? (:agents @state) agent-id)
      (do (sync-state-to-disk!)
          (swap! state assoc :active agent-id :history (load-agent-history agent-id))
          (ui-print "🔀 Switched to agent:" (name agent-id)))
      (ui-print "⚠️ Agent does not exist:" (name agent-id)))))

(defn fork-agent! [agent-id]
  (let [agent-id (sanitize-agent-id agent-id)]
    (if (contains? (:agents @state) agent-id)
      (ui-print "⚠️ Agent already exists:" (name agent-id))
      (do (sync-state-to-disk!)
          (swap! state assoc :active agent-id :history [])
          (swap! state update :agents conj agent-id)
          (ui-print "🆕 Forked and switched to new agent:" (name agent-id))))))

(defn subfork-agent! [agent-id]
  (let [agent-id (sanitize-agent-id agent-id)
        current-history (:history @state)]
    (if (contains? (:agents @state) agent-id)
      (ui-print "⚠️ Agent already exists:" (name agent-id))
      (do (sync-state-to-disk!)
          (swap! state assoc :active agent-id :history current-history)
          (swap! state update :agents conj agent-id)
          (save-agent-history! agent-id current-history)
          (ui-print "🌿 Subforked conversation to new agent:" (name agent-id))))))

(defn delete-agent! [agent-id]
  (let [agent-id (sanitize-agent-id agent-id)]
    (cond
      (= agent-id :main) (ui-print "⚠️ Cannot delete main agent.")
      (not (contains? (:agents @state) agent-id)) (ui-print "⚠️ Agent not found:" (name agent-id))
      :else (do (fs/delete-if-exists (agent-file agent-id))
                (swap! state update :agents disj agent-id)
                (when (= agent-id (:active @state))
                  (swap! state assoc :active :main :history (load-agent-history :main))
                  (ui-print "⚠️ Deleted active agent. Switched back to main."))
                (ui-print "🗑️ Deleted agent:" (name agent-id))))))

;;; === Commands ===

(defn print-help []
  (ui-print-always
    "\nAvailable commands:"
    "  /fork <agent>     - Create new agent and switch to it"
    "  /subfork <agent>  - Fork current conversation to new agent"
    "  /switch <agent>   - Switch to existing agent"
    "  /list             - List all agents"
    "  /delete <agent>   - Delete an agent"
    "  /models           - List available models"
    "  /model <name>     - Switch to model"
    "  /provider <name>  - Switch provider"
    "  /status           - Show current status"
    "  /quiet            - Toggle quiet mode"
    "  /normal           - Toggle normal mode"
    "  /help             - Show this help"
    "  /exit             - Exit program"
    "Otherwise, type any message to chat with the current agent."))

(defn list-models []
  (let [current-prov @current-provider
        current-mod @current-model]
    (if (= @ui-mode :quiet)
      (doseq [[provider provider-data] providers]
        (doseq [[model-key model-name] (:models provider-data)]
          (println (str (name provider) ":" (name model-key)))))
      (do (ui-print "🤖 Available models:")
          (doseq [[provider provider-data] providers]
            (ui-print (format "\n%s %s:" 
                             (if (= provider current-prov) "▶" " ")
                             (name provider)))
            (doseq [[model-key model-name] (:models provider-data)]
              (ui-print (format "  %s %s (%s)"
                               (if (and (= provider current-prov) (= model-key current-mod)) "●" "○")
                               (name model-key) model-name))))))))

(defn switch-model! [model-name]
  (let [model-key (keyword model-name)
        current-prov @current-provider
        available-models (get-in providers [current-prov :models])]
    (if (contains? available-models model-key)
      (do (reset! current-model model-key)
          (ui-print "🔀 Switched to model:" model-name))
      (ui-print "⚠️ Model not available for current provider:" model-name))))

(defn switch-provider! [provider-name]
  (let [provider-key (keyword provider-name)]
    (if (contains? providers provider-key)
      (do (reset! current-provider provider-key)
          (reset! current-model (first (keys (get-in providers [provider-key :models]))))
          (ui-print "🔀 Switched to provider:" provider-name "with model:" (name @current-model)))
      (ui-print "⚠️ Unknown provider:" provider-name))))

(defn show-status []
  (let [{:keys [provider model-key model-name]} (get-current-config)
        agent (:active @state)
        msg-count (count (:history @state))]
    (ui-print-always "📊 Status:")
    (ui-print-always "  Provider:" (name provider))
    (ui-print-always "  Model:" (name model-key) "(" model-name ")")
    (ui-print-always "  Agent:" (name agent) "(" msg-count "messages)")))

;;; === Main Loop ===

(defn dispatch [line]
  (let [line (str/trim line)]
    (cond
      (str/blank? line) nil
      
      (str/starts-with? line "/fork ")
      (let [arg (str/trim (subs line 6))]
        (if (seq arg) (fork-agent! arg) (ui-print "⚠️ Usage: /fork <agent>")))
      
      (str/starts-with? line "/subfork ")
      (let [arg (str/trim (subs line 9))]
        (if (seq arg) (subfork-agent! arg) (ui-print "⚠️ Usage: /subfork <agent>")))
      
      (str/starts-with? line "/switch ")
      (let [arg (str/trim (subs line 8))]
        (if (seq arg) (switch-agent! arg) (ui-print "⚠️ Usage: /switch <agent>")))
      
      (str/starts-with? line "/delete ")
      (let [arg (str/trim (subs line 8))]
        (if (seq arg) (delete-agent! arg) (ui-print "⚠️ Usage: /delete <agent>")))
      
      (str/starts-with? line "/model ")
      (let [arg (str/trim (subs line 7))]
        (if (seq arg) (switch-model! arg) (ui-print "⚠️ Usage: /model <model-name>")))
      
      (str/starts-with? line "/provider ")
      (let [arg (str/trim (subs line 10))]
        (if (seq arg) (switch-provider! arg) (ui-print "⚠️ Usage: /provider <openrouter|together>")))
      
      (= line "/list") (list-agents)
      (= line "/models") (list-models)
      (= line "/status") (show-status)
      (= line "/quiet") (do (reset! ui-mode :quiet) (ui-print-always "🔇 Quiet mode enabled"))
      (= line "/normal") (do (reset! ui-mode :normal) (ui-print-always "🔊 Normal mode enabled"))
      (= line "/help") (print-help)
      (= line "/exit") (do (sync-state-to-disk!) (ui-print "👋 Goodbye!") (System/exit 0))
      
      (str/starts-with? line "/") (ui-print "⚠️ Unknown command:" line)
      
      :else
      (let [agent (:active @state)
            old-history (:history @state)
            history-msgs (->> old-history
                              (map-indexed (fn [idx content]
                                            {:role (if (even? idx) "user" "assistant")
                                             :content content})))
            new-msgs (conj history-msgs {:role "user" :content line})
            response (call-llm new-msgs)]
        (if response
          (do (swap! state assoc :history (conj old-history line response))
              (println (quiet-response response)))
          (ui-print "⚠️ No response from LLM."))))))

(defn prompt []
  (if (= @ui-mode :quiet)
    (do (print "> ") (flush))
    (let [agent (:active @state)
          msg-count (count (:history @state))
          {:keys [provider model-key]} (get-current-config)]
      (print (format "\n[%s:%s | %s | %d msgs]> " 
                     (name provider) (name model-key) (name agent) msg-count))
      (flush))))

(defn -main [& args]
  (when (some #{"--quiet" "-q"} args)
    (reset! ui-mode :quiet))
  
  (swap! state assoc :history (load-agent-history :main))
  (ui-print "👾 Welcome to Agenty! Type /help for commands.")
  
  (loop []
    (prompt)
    (if-let [line (read-line)]
      (do (dispatch line) (recur))
      (ui-print "\n👋 Bye!"))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
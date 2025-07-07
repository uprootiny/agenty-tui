# Agenty Functionality Map

## Present (Working)

### Core State
```clojure
(defonce ui-mode (atom :normal))           ; :normal | :quiet
(defonce current-provider (atom :openrouter)) ; :openrouter | :together  
(defonce current-model (atom :claude-3-5-sonnet))
(defonce state (atom {:active :main :history [] :agents #{:main}}))
```

### Agent Management
```clojure
(defn fork-agent! [agent-id])      ; /fork <name> - new empty agent
(defn subfork-agent! [agent-id])   ; /subfork <name> - copy current history
(defn switch-agent! [agent-id])    ; /switch <name> - change active agent
(defn delete-agent! [agent-id])    ; /delete <name> - remove agent
(defn list-agents [])              ; /list - show all agents
```

### Model/Provider Control
```clojure
(defn switch-model! [model-name])     ; /model <name> - change model
(defn switch-provider! [prov-name])  ; /provider <name> - change provider
(defn list-models [])                ; /models - show available models
```

### API Integration
```clojure
(defn call-llm [messages])          ; OpenRouter + Together fallback
(def providers {:openrouter {...} :together {...}})
```

### UI Modes
```clojure
(defn ui-print [& args])            ; Normal mode only
(defn ui-print-always [& args])     ; Both modes
(defn quiet-response [content])     ; Prefix in normal mode
```

## Future (Planned)

### Enhanced TUI
```clojure
;; Spinners, colors, progress bars
(defn show-spinner [])
(defn colorize [text color])
(defn progress-bar [current total])
```

### Advanced Agent Operations
```clojure
(defn merge-agents! [from to])      ; Combine agent histories
(defn branch-conversation! [index]) ; Fork from specific message
(defn replay-conversation! [])      ; Re-run with different model
```

### Session Management
```clojure
(defn save-session! [name])         ; Named session snapshots
(defn load-session! [name])         ; Restore session state
(defn export-conversation! [fmt])   ; Export as md/json/etc
```

### Configuration
```clojure
(defn configure! [key val])         ; Runtime config changes
(defn add-provider! [config])       ; Custom API endpoints
(defn add-model! [provider model])  ; Register new models
```

## Architecture Notes

- Single 300-line babashka script
- Persistent agent storage in `~/.agenty-agents/`
- Dual UI modes: quiet (automation) + normal (interactive)
- Provider fallback chain for reliability
- Git-safe (secrets in .env, .gitignored)
# Agenty TUI

Terse and nimble Clojure TUI agent manager with Claude-like experience.

## Quick Start

```bash
git clone https://github.com/uprootiny/agenty-tui.git
cd agenty-tui

# Set your API keys
export OPENROUTER_API_KEY=your-openrouter-key
export TOGETHER_API_KEY=your-together-key  # optional fallback

# Or create .env file:
echo "OPENROUTER_API_KEY=your-key" > .env

# Run
./agenty.bb --quiet    # Minimal output
./agenty.bb           # Full TUI
```

## Features

- **Multi-agent conversations** with forking and switching
- **Dual UI modes**: quiet (automation-friendly) and normal (interactive)
- **Multiple AI providers**: OpenRouter (primary) + Together AI (fallback)
- **Model switching**: Claude, GPT-4, Llama models
- **Persistent storage**: Agent histories saved to disk

## Commands

```
/fork <name>      - Create new agent
/subfork <name>   - Fork current conversation 
/switch <name>    - Switch to agent
/delete <name>    - Delete agent
/list             - List all agents
/models           - Show available models
/model <name>     - Switch model
/provider <name>  - Switch provider
/status           - Show current status
/quiet            - Enable quiet mode
/normal           - Enable normal mode
/help             - Show help
/exit             - Exit
```

Single-file babashka script, ready for Claude Code fallback scenarios.
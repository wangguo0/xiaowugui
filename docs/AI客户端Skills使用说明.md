# AI 客户端 Skills 使用说明

本文说明如何把一套 `Skills` 复用到主流 AI 编程客户端里。

这里的 `Skill` 指给 AI Agent 使用的能力包，不是 App 运行时依赖。典型目录如下：

```text
skills/
  webhome-homepage-builder/
    SKILL.md
    references/
    scripts/
    assets/
  webhome-extension-builder/
    SKILL.md
    references/
    scripts/
    assets/
```

`SKILL.md` 是入口说明，负责告诉 AI 这个 Skill 何时使用、必须读哪些参考文件、优先运行哪些脚本、输出和校验要求是什么。`references/` 放长文档，`scripts/` 放可复用校验/生成脚本，`assets/` 放模板和示例。

## 1. 推荐共享方式

如果团队要跨客户端使用同一套 Skills，推荐保留一份 canonical 目录，例如：

```text
docs/ai-skills/
  webhome-homepage-builder/
    SKILL.md
    references/
    scripts/
    assets/
```

然后按不同客户端建立很薄的适配层：

- 原生支持 `SKILL.md` 的客户端：直接复制或软链到它要求的 skills 目录。
- 不原生支持的客户端：在 Rules / Instructions 里写“遇到某类任务时先阅读对应 `SKILL.md`”，让它把 `SKILL.md` 当项目规范执行。
- 不支持读取本地文件的聊天客户端：上传 `SKILL.md` 和必要 references，或者把核心规则放到项目指令里。

不要把完整 Skill 内容复制到每个客户端规则里。更稳的方式是维护一份 Skill 源文件，其他客户端只写路由说明，减少文档漂移。

## 2. 客户端接入方式总览

| 客户端 | 是否原生 Skills | 推荐接入方式 |
| --- | --- | --- |
| Codex CLI / Codex | 支持 | 放到 `$CODEX_HOME/skills/<skill>/SKILL.md`，通常是 `~/.codex/skills/` |
| Claude Code | 版本支持时可用 | 优先使用 Claude Skills；否则放到 `CLAUDE.md` 或项目 Rules 中引用 `SKILL.md` |
| Cursor | 不按 `SKILL.md` 自动发现 | 用 `.cursor/rules/*.mdc` 路由到对应 Skill |
| Windsurf | 不按 `SKILL.md` 自动发现 | 用 Workspace Rules / Memories 路由到对应 Skill |
| Cline / Roo Code | 不按 `SKILL.md` 自动发现 | 用 `.clinerules`、`.clinerules/`、`.roo/rules/` 路由到对应 Skill |
| Gemini CLI / Gemini Code Assist | 不按 `SKILL.md` 自动发现 | 用 `GEMINI.md` 或项目指令引用对应 Skill |
| GitHub Copilot Chat | 不按 `SKILL.md` 自动发现 | 用 `.github/copilot-instructions.md` 和 prompt files |
| ChatGPT / 自定义 GPT | 不读取本地 skills 目录 | 项目指令 + 上传 Skill 文件；脚本和 assets 需显式提供 |
| Aider | 不按 `SKILL.md` 自动发现 | 用 `CONVENTIONS.md` 或启动参数 `--read` 引入 Skill |

客户端能力变化很快。判断标准只有一个：它是否会自动发现并阅读 `SKILL.md`。会，就走原生 Skills；不会，就用规则文件显式要求它读。

## 3. Codex CLI / Codex

Codex 的推荐方式是把 Skill 放到 `$CODEX_HOME/skills`：

```bash
mkdir -p ~/.codex/skills
cp -R docs/ai-skills/webhome-homepage-builder ~/.codex/skills/
cp -R docs/ai-skills/webhome-extension-builder ~/.codex/skills/
```

也可以用软链，方便仓库内维护一份源文件：

```bash
mkdir -p ~/.codex/skills
ln -s "$PWD/docs/ai-skills/webhome-homepage-builder" ~/.codex/skills/webhome-homepage-builder
```

使用方式：

```text
使用 webhome-homepage-builder skill，修改 demo/nostr.html 的播放页壁纸逻辑，并运行兼容检查。
```

Codex 会根据 Skill 的 `description` 自动判断是否触发。为了减少误判，`SKILL.md` 的描述要写得具体，例如“WebHome homePage / nostr.html / fm SDK / 透明 WebView / TV remote focus”。

## 4. Claude Code

如果当前 Claude Code 版本支持 Skills，优先使用它的原生 Skills 目录；常见做法是把 Skill 放到用户级或项目级 skills 目录，例如：

```bash
mkdir -p .claude/skills
cp -R docs/ai-skills/webhome-homepage-builder .claude/skills/
```

如果当前版本没有启用 Skills，就在 `CLAUDE.md` 写一个轻量路由：

```md
# Project Skills

When the task involves WebHome homepage, nostr.html, fm SDK, transparent WebView, TV focus, or PanSou homepage work:
1. Read `docs/ai-skills/webhome-homepage-builder/SKILL.md` completely before editing.
2. Follow its required validation commands.
3. Prefer scripts and templates under that skill directory.

When the task involves WebHome injected extensions, manifests, native play buttons, pan routing, or fm.vodInline:
1. Read `docs/ai-skills/webhome-extension-builder/SKILL.md` completely before editing.
2. Follow its packaging and compatibility checklist.
```

Claude Code 的重点是让它先读 `SKILL.md`，不要只把 Skill 名字写进指令里。

## 5. Cursor

Cursor 主要通过 Project Rules 工作。推荐新建：

```text
.cursor/rules/webhome-skills.mdc
```

示例：

```md
---
description: WebHome skills routing
globs:
  - "demo/**/*.html"
  - "docs/webhome-extension/**/*"
  - "app/src/main/java/com/fongmi/android/tv/web/**/*.java"
alwaysApply: false
---

When working on WebHome homepage, nostr.html, fm SDK usage, transparent WebView, TV remote focus, PanSou, or playback artwork:
- Read `docs/ai-skills/webhome-homepage-builder/SKILL.md` completely before editing.
- Run the validation commands required by that Skill.

When working on WebHome injected extensions, manifest config, native play buttons, pan routing, or vodInline:
- Read `docs/ai-skills/webhome-extension-builder/SKILL.md` completely before editing.
- Follow the extension compatibility rules in that Skill.
```

如果 Cursor 没有自动读到规则，直接在对话里点名：

```text
先阅读 docs/ai-skills/webhome-homepage-builder/SKILL.md，再按里面的流程处理。
```

## 6. Windsurf

Windsurf 用 Workspace Rules / Memories 约束 Cascade。不同版本的规则文件位置可能不同，常见是 `.windsurfrules` 或 `.windsurf/rules/*.md`。

示例：

```md
# WebHome Skills

For WebHome homepage work, read `docs/ai-skills/webhome-homepage-builder/SKILL.md` before making changes.
For WebHome extension work, read `docs/ai-skills/webhome-extension-builder/SKILL.md` before making changes.

Treat the Skill validation checklist as required. Do not skip compatibility checks for `demo/nostr.html`.
```

Windsurf 没有原生 Skills 时，不要期待它自动解析 `SKILL.md` frontmatter；需要在规则里明确“先读哪个文件”。

## 7. Cline / Roo Code

Cline 常用 `.clinerules` 或 `.clinerules/`，Roo Code 常用 `.roo/rules/`。

Cline 示例：

```text
.clinerules
```

```md
When editing WebHome homepage or demo/nostr.html:
Read `docs/ai-skills/webhome-homepage-builder/SKILL.md` first and follow its validation requirements.

When editing WebHome extensions or docs/webhome-extension:
Read `docs/ai-skills/webhome-extension-builder/SKILL.md` first.
```

Roo Code 示例：

```text
.roo/rules/webhome-skills.md
```

```md
Use the WebHome homepage Skill for tasks involving homePage, nostr.html, fm SDK, PanSou, Nostr/TMDB, playback artwork, or TV focus.
Use the WebHome extension Skill for tasks involving injected scripts, extension manifests, pan routing, native play buttons, or vodInline.
Always read the corresponding `SKILL.md` before edits.
```

## 8. Gemini CLI / Gemini Code Assist

Gemini CLI 通常读取项目里的 `GEMINI.md`。可以在仓库根目录加入：

```md
# Skills

For WebHome homepage tasks:
Read `docs/ai-skills/webhome-homepage-builder/SKILL.md` before editing.

For WebHome extension tasks:
Read `docs/ai-skills/webhome-extension-builder/SKILL.md` before editing.

If a Skill references `scripts/`, prefer running those scripts instead of reimplementing the checks.
```

Gemini Code Assist 或网页端如果不读取本地 `GEMINI.md`，需要把同样内容放到项目指令或对话开头。

## 9. GitHub Copilot Chat

Copilot Chat 推荐使用：

```text
.github/copilot-instructions.md
.github/prompts/*.prompt.md
```

`.github/copilot-instructions.md` 示例：

```md
When a task touches WebHome homepage, Nostr demo, fm SDK, or Android WebView compatibility:
Read `docs/ai-skills/webhome-homepage-builder/SKILL.md` and follow it.

When a task touches WebHome extension scripts or manifests:
Read `docs/ai-skills/webhome-extension-builder/SKILL.md` and follow it.
```

也可以为常用任务建 prompt file：

```text
.github/prompts/webhome-homepage.prompt.md
```

```md
Read `docs/ai-skills/webhome-homepage-builder/SKILL.md`.
Then inspect the requested files, make the change, run the required validation, and summarize the result.
```

## 10. ChatGPT / 自定义 GPT

普通 ChatGPT 不会读取本机的 `~/.codex/skills`、`.claude/skills` 或仓库文件，除非你把文件上传或粘贴进去。

推荐方式：

1. 在 Project instructions / 自定义 GPT Instructions 里写 Skill 路由。
2. 把 `SKILL.md` 和必要 `references/` 上传为知识文件。
3. 如果 Skill 依赖 `scripts/` 或 `assets/`，把脚本和模板也作为文件上传，或者在任务中明确提供路径。
4. 每次任务开头写清楚使用哪个 Skill，例如“使用 WebHome homepage Skill，先阅读上传的 SKILL.md”。

注意：ChatGPT 不能直接运行你本地仓库里的校验脚本。需要在有终端能力的客户端里执行，或者让它输出命令给你运行。

## 11. Aider

Aider 不会自动发现 `SKILL.md`，但可以把 Skill 作为上下文文件读入：

```bash
aider --read docs/ai-skills/webhome-homepage-builder/SKILL.md demo/nostr.html
```

也可以维护 `CONVENTIONS.md`：

```md
For WebHome homepage work, read and follow `docs/ai-skills/webhome-homepage-builder/SKILL.md`.
For WebHome extension work, read and follow `docs/ai-skills/webhome-extension-builder/SKILL.md`.
```

## 12. 写 Skill 时要兼容多客户端

为了让一份 Skill 在不同客户端里都能工作，`SKILL.md` 建议遵守这些约束：

- `description` 写清触发场景，不要只写“通用开发助手”。
- 把长内容拆到 `references/`，入口只写路由、必读文件、工作流、校验命令。
- 脚本路径使用相对路径，并说明“相对 Skill 目录解析”。
- 输出要求要具体，例如要修改哪些文件、必须跑哪些命令、失败时如何报告。
- 不要把账号、Cookie、Token、私有接口密钥写进 Skill。
- 不要依赖某个客户端专属变量；路径、命令和检查清单尽量用普通 shell 和仓库相对路径表达。
- 对旧 WebView、Android 构建、前端兼容这类硬约束，要写成 release blocker，而不是建议。

## 13. 最小可用路由模板

如果某个客户端没有原生 Skills，只要支持项目规则，就可以用这个模板：

```md
# Skills Routing

When the task matches `<task type>`:
1. Read `<path>/<skill-name>/SKILL.md` completely.
2. Read only the references that `SKILL.md` asks for.
3. Reuse scripts/assets/templates under that Skill when available.
4. Make the requested change.
5. Run the validation commands required by that Skill.
6. Report changed files, validation result, commit/tag if this repo requires it.
```

这个模板的核心是“先读 Skill，再执行任务”。只写“请遵守 Skills”通常不够，很多客户端不会自动知道 Skills 在哪里。

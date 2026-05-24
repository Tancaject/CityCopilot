# CityCopilot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Transform the hmdp tutorial-clone project into CityCopilot, a Spring AI Alibaba based "acting" AI Agent platform for local life services, suitable for 2026 校招 Java backend resume. v0.1 MVP shippable by end of week 3; v0.2 full version by week 5.

**Architecture:** Modular monolith with 4 Maven modules (`core`, `agent`, `mcp-server`, `eval`). Tool Registry driven by `@AgentTool` annotation produces both LLM Function Calling tools and MCP server tools from a single source. Orchestrator supports ReAct (single-loop) and Plan-Execute-Replan (multi-step) modes, selected by router. Dialog Agent serves users via SSE; Ops Agent consumes Redis Stream for async work; Critic Agent guards high-risk operations.

**Tech Stack:**
- Java 17, Spring Boot 3.2.2 (existing), MyBatis-Plus, Redisson, Hutool, MySQL, Redis
- Spring AI Alibaba (starter-dashscope) for LLM access
- Spring AI MCP server starter for MCP protocol
- Resilience4j for tool-call resilience
- Micrometer + Prometheus + Grafana for observability
- Vue 3 + TypeScript + Vite (existing frontend) extended with SSE client and traces dashboard

**Reference spec:** `docs/superpowers/specs/2026-05-24-citycopilot-design.md`

---

## Phase Map

| Week | Phases | Cumulative state |
|---|---|---|
| 1 | 0, 1, 2, 3, 4 | Multi-module, renamed, LLM smoke test passes |
| 2 | 5, 6, 7, 8, 9, 10, 11, 12 | **v0.1 MVP**: ReAct + 5 tools + SSE + traces |
| 3 | 13, 14, 15, 16 | **v0.1.5**: Plan-Execute + 15-20 tools + MCP server + Resilience4j |
| 4 | 17, 18, 19, 20 | Ops/Critic Agent, Eval Harness, Prometheus |
| 5 | 21, 22 | **v0.2 full**: Traces dashboard, demo video, resume description |

---

## Phase 0: Pre-flight Compatibility POC

**Goal:** Verify Spring AI Alibaba + Spring AI MCP server starter both compatible with existing Spring Boot 3.2.2 / MyBatis-Plus / Redisson / Hutool dependencies before committing to the rewrite. This is risk-mitigation per spec §8.

### Task 0.1: Create scratch branch and POC pom

**Files:**
- Create: `/tmp/citycopilot-poc/pom.xml` (scratch, will be discarded)

- [ ] **Step 1: Make a scratch directory**

```bash
mkdir -p /tmp/citycopilot-poc/src/main/java/poc
mkdir -p /tmp/citycopilot-poc/src/main/resources
cd /tmp/citycopilot-poc
```

- [ ] **Step 2: Write a minimal POC pom that mirrors existing constraints + adds AI deps**

Create `/tmp/citycopilot-poc/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>poc</groupId><artifactId>poc</artifactId><version>1</version>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.2.2</version>
    </parent>
    <properties>
        <java.version>17</java.version>
        <spring-ai-alibaba.version>1.0.0-M6</spring-ai-alibaba.version>
        <spring-ai.version>1.0.0-M6</spring-ai.version>
    </properties>
    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>com.alibaba.cloud.ai</groupId>
                <artifactId>spring-ai-alibaba-bom</artifactId>
                <version>${spring-ai-alibaba.version}</version>
                <type>pom</type><scope>import</scope>
            </dependency>
            <dependency>
                <groupId>org.springframework.ai</groupId>
                <artifactId>spring-ai-bom</artifactId>
                <version>${spring-ai.version}</version>
                <type>pom</type><scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
    <dependencies>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-redis</artifactId></dependency>
        <dependency><groupId>com.mysql</groupId><artifactId>mysql-connector-j</artifactId></dependency>
        <dependency><groupId>com.baomidou</groupId><artifactId>mybatis-plus-spring-boot3-starter</artifactId><version>3.5.5</version></dependency>
        <dependency><groupId>org.redisson</groupId><artifactId>redisson</artifactId><version>4.3.0</version></dependency>
        <dependency><groupId>cn.hutool</groupId><artifactId>hutool-all</artifactId><version>5.8.26</version></dependency>
        <dependency><groupId>com.alibaba.cloud.ai</groupId><artifactId>spring-ai-alibaba-starter-dashscope</artifactId></dependency>
        <dependency><groupId>org.springframework.ai</groupId><artifactId>spring-ai-starter-mcp-server</artifactId></dependency>
    </dependencies>
    <repositories>
        <repository><id>spring-milestones</id><url>https://repo.spring.io/milestone</url></repository>
    </repositories>
</project>
```

- [ ] **Step 3: Add a minimal Application class**

Create `/tmp/citycopilot-poc/src/main/java/poc/PocApplication.java`:

```java
package poc;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PocApplication {
    public static void main(String[] args) { SpringApplication.run(PocApplication.class, args); }
}
```

Create `/tmp/citycopilot-poc/src/main/resources/application.yml`:

```yaml
spring:
  ai:
    dashscope:
      api-key: dummy-for-resolution-only
  datasource:
    url: jdbc:mysql://localhost:3306/dummy
    username: u
    password: p
  data:
    redis:
      host: localhost
      port: 6379
server:
  port: 9999
```

- [ ] **Step 4: Build to verify all deps resolve and there are no conflicts**

```bash
cd /tmp/citycopilot-poc
mvn -q -DskipTests package
```

Expected: BUILD SUCCESS. If FAIL, the most likely cause is a Spring AI Alibaba M-release version mismatch with the local Maven repo — check `https://github.com/alibaba/spring-ai-alibaba/releases` for the latest stable, update `spring-ai-alibaba.version` accordingly, and retry.

**Decision gate:** Only proceed to Phase 1 once `mvn package` succeeds. If hard-blocked after 2 attempts, fall back to `org.springframework.ai:spring-ai-openai-spring-boot-starter` (OpenAI-compatible client pointing at DashScope's OpenAI-compatible endpoint), and update the plan's LLM dependency choice in Phase 4 accordingly.

- [ ] **Step 5: Record the working version pins**

Write down the exact `spring-ai-alibaba.version`, `spring-ai.version`, and any repo URL needed. These get copied into Phase 2 parent pom.

```bash
echo "spring-ai-alibaba.version=$(mvn -q help:evaluate -Dexpression=spring-ai-alibaba.version -DforceStdout)" > /tmp/citycopilot-poc/PINNED_VERSIONS.txt
echo "spring-ai.version=$(mvn -q help:evaluate -Dexpression=spring-ai.version -DforceStdout)" >> /tmp/citycopilot-poc/PINNED_VERSIONS.txt
cat /tmp/citycopilot-poc/PINNED_VERSIONS.txt
```

- [ ] **Step 6: Tear down POC**

```bash
rm -rf /tmp/citycopilot-poc/src /tmp/citycopilot-poc/target
# Keep pom.xml + PINNED_VERSIONS.txt as reference, delete in Phase 22 cleanup
```

---

## Phase 1: Git initialization

**Goal:** Put the project under version control before any restructure, so every later phase can commit small steps and roll back cleanly.

### Task 1.1: Initialize repository

**Files:**
- Modify: `D:\programming\hmdp\.gitignore`
- Create: `D:\programming\hmdp\.git/` (via git init)

- [ ] **Step 1: Verify not already a git repo**

```bash
cd /d/programming/hmdp
git status
```

Expected: `fatal: not a git repository (or any of the parent directories): .git`

- [ ] **Step 2: Initialize**

```bash
cd /d/programming/hmdp
git init -b main
```

Expected: `Initialized empty Git repository in D:/programming/hmdp/.git/`

- [ ] **Step 3: Augment .gitignore for IDE / build / OS artifacts**

Read existing `.gitignore`. If missing entries, append:

```gitignore
# IDE
.idea/
.vscode/
*.iml

# Build
target/
*.class
build/

# Frontend build
frontend/node_modules/
frontend/dist/

# OS
.DS_Store
Thumbs.db

# Secrets / local config
application-local.yml
application-secret.yml
.env
.env.local

# Logs
*.log
logs/
```

- [ ] **Step 4: Confirm secrets are not staged**

```bash
cd /d/programming/hmdp
git status --short | head -50
grep -r "API_KEY\|api-key\|password" src/main/resources/ 2>/dev/null | grep -v "example\|placeholder"
```

If secrets are present in `application.yml`, move them to `application-local.yml` and reference via `${VAR:default}` in the committed file.

- [ ] **Step 5: First commit — record current state before restructure**

```bash
cd /d/programming/hmdp
git add .gitignore
git add pom.xml README.md hmdp.sql
git add src/ docs/ deploy/ frontend/package.json frontend/package-lock.json frontend/tsconfig.json frontend/vite.config.ts frontend/index.html frontend/src/ frontend/public/
git status --short | wc -l
git commit -m "chore: initial commit of hmdp baseline before CityCopilot refactor"
```

Expected: A single commit on `main` with the current project state.

---

## Phase 2: Maven multi-module restructure

**Goal:** Convert single-module hmdp into a multi-module project: `core` (existing business), `agent` (new), `mcp-server` (new), `eval` (new). All existing tests still pass after restructure.

### Task 2.1: Move existing source into a `core` module

**Files:**
- Create: `D:\programming\hmdp\core\pom.xml`
- Move: `D:\programming\hmdp\src\` → `D:\programming\hmdp\core\src\`
- Modify: `D:\programming\hmdp\pom.xml` (becomes aggregator parent)

- [ ] **Step 1: Move existing src into core/**

```bash
cd /d/programming/hmdp
mkdir core
git mv src core/src
git status --short | head -10
```

Expected: A list of `R  src/... -> core/src/...` rename entries.

- [ ] **Step 2: Create `core/pom.xml` mirroring current single-module pom but as a child**

Create `D:\programming\hmdp\core\pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.citycopilot</groupId>
        <artifactId>city-copilot-parent</artifactId>
        <version>1.0-SNAPSHOT</version>
    </parent>

    <artifactId>core</artifactId>
    <packaging>jar</packaging>

    <dependencies>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-redis</artifactId></dependency>
        <dependency><groupId>org.apache.commons</groupId><artifactId>commons-pool2</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
        <dependency><groupId>com.mysql</groupId><artifactId>mysql-connector-j</artifactId></dependency>
        <dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><optional>true</optional></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
        <dependency><groupId>com.baomidou</groupId><artifactId>mybatis-plus-spring-boot3-starter</artifactId><version>3.5.5</version></dependency>
        <dependency><groupId>cn.hutool</groupId><artifactId>hutool-all</artifactId><version>5.8.26</version></dependency>
        <dependency><groupId>org.aspectj</groupId><artifactId>aspectjweaver</artifactId></dependency>
        <dependency><groupId>org.redisson</groupId><artifactId>redisson</artifactId><version>4.3.0</version></dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId></exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 3: Rewrite root `pom.xml` as aggregator parent**

Read existing `D:\programming\hmdp\pom.xml`, then overwrite with:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.citycopilot</groupId>
    <artifactId>city-copilot-parent</artifactId>
    <version>1.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.2.2</version>
        <relativePath/>
    </parent>

    <modules>
        <module>core</module>
        <module>agent</module>
        <module>mcp-server</module>
        <module>eval</module>
    </modules>

    <properties>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <!-- Pin from Phase 0 POC output -->
        <spring-ai-alibaba.version>1.0.0-M6</spring-ai-alibaba.version>
        <spring-ai.version>1.0.0-M6</spring-ai.version>
        <resilience4j.version>2.2.0</resilience4j.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>com.alibaba.cloud.ai</groupId>
                <artifactId>spring-ai-alibaba-bom</artifactId>
                <version>${spring-ai-alibaba.version}</version>
                <type>pom</type><scope>import</scope>
            </dependency>
            <dependency>
                <groupId>org.springframework.ai</groupId>
                <artifactId>spring-ai-bom</artifactId>
                <version>${spring-ai.version}</version>
                <type>pom</type><scope>import</scope>
            </dependency>
            <dependency>
                <groupId>io.github.resilience4j</groupId>
                <artifactId>resilience4j-bom</artifactId>
                <version>${resilience4j.version}</version>
                <type>pom</type><scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <repositories>
        <repository>
            <id>spring-milestones</id>
            <name>Spring Milestones</name>
            <url>https://repo.spring.io/milestone</url>
            <snapshots><enabled>false</enabled></snapshots>
        </repository>
    </repositories>
</project>
```

- [ ] **Step 4: Create empty stub modules so aggregate build works**

For each of `agent`, `mcp-server`, `eval`:

```bash
cd /d/programming/hmdp
for m in agent mcp-server eval; do
  mkdir -p "$m/src/main/java" "$m/src/test/java"
done
```

Create `D:\programming\hmdp\agent\pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.citycopilot</groupId>
        <artifactId>city-copilot-parent</artifactId>
        <version>1.0-SNAPSHOT</version>
    </parent>
    <artifactId>agent</artifactId>
    <packaging>jar</packaging>
    <dependencies>
        <dependency><groupId>com.citycopilot</groupId><artifactId>core</artifactId><version>${project.version}</version></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
        <dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><optional>true</optional></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
    </dependencies>
</project>
```

Create `D:\programming\hmdp\mcp-server\pom.xml` (mirror of agent's pom but with `<artifactId>mcp-server</artifactId>` and an extra dep on agent):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.citycopilot</groupId>
        <artifactId>city-copilot-parent</artifactId>
        <version>1.0-SNAPSHOT</version>
    </parent>
    <artifactId>mcp-server</artifactId>
    <packaging>jar</packaging>
    <dependencies>
        <dependency><groupId>com.citycopilot</groupId><artifactId>agent</artifactId><version>${project.version}</version></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
    </dependencies>
</project>
```

Create `D:\programming\hmdp\eval\pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.citycopilot</groupId>
        <artifactId>city-copilot-parent</artifactId>
        <version>1.0-SNAPSHOT</version>
    </parent>
    <artifactId>eval</artifactId>
    <packaging>jar</packaging>
    <dependencies>
        <dependency><groupId>com.citycopilot</groupId><artifactId>agent</artifactId><version>${project.version}</version></dependency>
        <dependency><groupId>org.yaml</groupId><artifactId>snakeyaml</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
    </dependencies>
</project>
```

- [ ] **Step 5: Add stub package-info.java in each new module to keep them as valid Java sources**

```bash
cd /d/programming/hmdp
mkdir -p agent/src/main/java/com/citycopilot/agent
mkdir -p mcp-server/src/main/java/com/citycopilot/mcp
mkdir -p eval/src/main/java/com/citycopilot/eval

cat > agent/src/main/java/com/citycopilot/agent/package-info.java <<'EOF'
package com.citycopilot.agent;
EOF
cat > mcp-server/src/main/java/com/citycopilot/mcp/package-info.java <<'EOF'
package com.citycopilot.mcp;
EOF
cat > eval/src/main/java/com/citycopilot/eval/package-info.java <<'EOF'
package com.citycopilot.eval;
EOF
```

- [ ] **Step 6: Aggregate build**

```bash
cd /d/programming/hmdp
mvn -q clean package -DskipTests
```

Expected: BUILD SUCCESS, with output showing all four modules built. The `core` module produces a JAR equivalent to the old single-module artifact.

If `core` fails to find some test resource path because tests assumed root-relative `src/`, those will be caught when running tests in Step 7.

- [ ] **Step 7: Run existing tests (if any) to confirm no regression**

```bash
cd /d/programming/hmdp
mvn -q -pl core test
```

Expected: BUILD SUCCESS, all existing tests pass.

- [ ] **Step 8: Commit**

```bash
cd /d/programming/hmdp
git add pom.xml core/pom.xml agent/ mcp-server/ eval/
git add -u   # picks up the renames into core/src/
git commit -m "refactor: split into Maven multi-module (core/agent/mcp-server/eval)"
```

---

## Phase 3: Rename hmdp → city-copilot

**Goal:** Change the externally visible identity from `hmdp` / 黑马点评 to `city-copilot` / CityCopilot. Package rename, main application class, README.

### Task 3.1: Rename Java package `com.hmdp` → `com.citycopilot.core`

**Files:**
- Move: every file under `core/src/main/java/com/hmdp/` → `core/src/main/java/com/citycopilot/core/`
- Move: every file under `core/src/test/java/com/hmdp/` → `core/src/test/java/com/citycopilot/core/` (if exists)
- Modify: every `package com.hmdp...;` and `import com.hmdp...;` declaration to `com.citycopilot.core...`

- [ ] **Step 1: Bulk move directories**

```bash
cd /d/programming/hmdp
mkdir -p core/src/main/java/com/citycopilot
git mv core/src/main/java/com/hmdp core/src/main/java/com/citycopilot/core
if [ -d core/src/test/java/com/hmdp ]; then
  mkdir -p core/src/test/java/com/citycopilot
  git mv core/src/test/java/com/hmdp core/src/test/java/com/citycopilot/core
fi
```

- [ ] **Step 2: Find-and-replace package declarations and imports**

```bash
cd /d/programming/hmdp
# package declarations
find core/src -type f -name "*.java" -exec sed -i 's|^package com\.hmdp|package com.citycopilot.core|' {} \;
# imports
find core/src -type f -name "*.java" -exec sed -i 's|import com\.hmdp\.|import com.citycopilot.core.|g' {} \;
find core/src -type f -name "*.java" -exec sed -i 's|import static com\.hmdp\.|import static com.citycopilot.core.|g' {} \;
```

- [ ] **Step 3: Update MyBatis mapper XML namespaces**

```bash
cd /d/programming/hmdp
find core/src/main/resources/mapper -type f -name "*.xml" -exec sed -i 's|com\.hmdp\.mapper|com.citycopilot.core.mapper|g' {} \;
```

- [ ] **Step 4: Update `application.yml` MyBatis-Plus type-aliases-package and any explicit base-package references**

Read `core/src/main/resources/application.yml` and replace any `com.hmdp` with `com.citycopilot.core`. Same for `application.properties` if present.

- [ ] **Step 5: Rename main application class**

```bash
cd /d/programming/hmdp
git mv core/src/main/java/com/citycopilot/core/HmdpApplication.java core/src/main/java/com/citycopilot/core/CityCopilotApplication.java
sed -i 's|class HmdpApplication|class CityCopilotApplication|g' core/src/main/java/com/citycopilot/core/CityCopilotApplication.java
sed -i 's|HmdpApplication\.class|CityCopilotApplication.class|g' core/src/main/java/com/citycopilot/core/CityCopilotApplication.java
```

(Adjust file name if main class is named differently in current code — verify with `grep -r "@SpringBootApplication" core/src/main/java/`.)

- [ ] **Step 6: Update any `@ComponentScan` / `@MapperScan` annotations**

```bash
cd /d/programming/hmdp
grep -rn "com\.hmdp" core/src/main/java/ core/src/main/resources/ 2>/dev/null
```

Expected: zero matches. If any remain (most likely `@MapperScan("com.hmdp.mapper")`), edit them by hand.

- [ ] **Step 7: Rebuild and run tests**

```bash
cd /d/programming/hmdp
mvn -q -pl core clean test
```

Expected: BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
cd /d/programming/hmdp
git add core/
git commit -m "refactor: rename package com.hmdp -> com.citycopilot.core and main class HmdpApplication -> CityCopilotApplication"
```

### Task 3.2: Rewrite README

**Files:**
- Modify: `D:\programming\hmdp\README.md`

- [ ] **Step 1: Replace README with project pitch + architecture overview**

Overwrite `README.md`:

```markdown
# CityCopilot

> An "acting" AI Agent platform for local life services. Users express intent in natural language ("nearby hot pot under 50 yuan with vouchers, place an order for me") and the agent autonomously plans, calls tools, and executes transactions.

## What this is

Spring Boot 3 + Spring AI Alibaba based AI Agent system with:

- **Tool Registry**: `@AgentTool` annotation exposes business services as both LLM Function Calling tools and MCP server tools from a single source
- **Orchestrator**: ReAct (single-loop) + Plan-Execute-Replan (multi-step) modes, selected automatically by intent complexity
- **Multi-Agent**: Dialog Agent for user conversation, Ops Agent for async operations (Redis Stream driven), Critic Agent for high-risk action review
- **MCP Server**: business capabilities exposed to Claude / Cursor / any MCP client
- **Eval Harness**: 30+ scripted cases with tool-call accuracy, latency, and token metrics
- **Production-grade backend**: Redis Lua seckill, Redisson distributed lock, Redis GEO nearby search, Resilience4j circuit breaker, Micrometer + Prometheus observability

## Modules

| Module | Responsibility |
|---|---|
| `core` | Domain services: Shop / Voucher / Order / Geo / Blog / User / Audit |
| `agent` | Tool Registry, Orchestrator, Multi-Agent, SSE chat API |
| `mcp-server` | Model Context Protocol server, exposes core capabilities to MCP clients |
| `eval` | Evaluation harness CLI and YAML case library |

## Architecture

See `docs/superpowers/specs/2026-05-24-citycopilot-design.md`.

## Quick start

Prerequisites: JDK 17, MySQL 8, Redis 7, DashScope API key.

```bash
# Backend
export DASHSCOPE_API_KEY=sk-...
mvn -pl core,agent spring-boot:run -pl agent

# Frontend
cd frontend && npm install && npm run dev
```

Open `http://localhost:5173`.

## Status

This project derives from the open-source hmdp tutorial code but has been substantially re-architected for production-grade backend + AI Agent capabilities. See commit history for the diff vs the original.
```

- [ ] **Step 2: Commit**

```bash
cd /d/programming/hmdp
git add README.md
git commit -m "docs: rewrite README as CityCopilot project pitch"
```

---

## Phase 4: Spring AI Alibaba integration smoke test

**Goal:** End-to-end LLM call works. `/agent/ping` returns a response from DashScope.

### Task 4.1: Wire Spring AI Alibaba in `agent` module

**Files:**
- Modify: `agent/pom.xml`
- Create: `agent/src/main/resources/application-agent.yml`
- Create: `agent/src/main/java/com/citycopilot/agent/config/LlmConfig.java`
- Create: `agent/src/main/java/com/citycopilot/agent/sse/PingController.java`

- [ ] **Step 1: Add Spring AI Alibaba dep to `agent/pom.xml`**

In `agent/pom.xml`, inside `<dependencies>`, add:

```xml
<dependency>
    <groupId>com.alibaba.cloud.ai</groupId>
    <artifactId>spring-ai-alibaba-starter-dashscope</artifactId>
</dependency>
```

- [ ] **Step 2: Add agent-specific YAML config**

Create `agent/src/main/resources/application-agent.yml`:

```yaml
spring:
  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY:}
      chat:
        options:
          model: qwen-plus
          temperature: 0.3

citycopilot:
  agent:
    max-react-steps: 5
    token-budget: 8000
    summarize-threshold: 6000
```

- [ ] **Step 3: Import agent profile into core's `application.yml`**

In `core/src/main/resources/application.yml`, ensure profile activation includes the agent profile. Add or modify:

```yaml
spring:
  profiles:
    include: agent
```

(If existing application.yml already structured differently, place an `@PropertySource` annotation on `agent/src/main/java/com/citycopilot/agent/config/LlmConfig.java` referencing `classpath:application-agent.yml`.)

- [ ] **Step 4: Add ChatClient bean**

Create `agent/src/main/java/com/citycopilot/agent/config/LlmConfig.java`:

```java
package com.citycopilot.agent.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LlmConfig {

    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }
}
```

- [ ] **Step 5: Add /agent/ping controller for smoke test**

Create `agent/src/main/java/com/citycopilot/agent/sse/PingController.java`:

```java
package com.citycopilot.agent.sse;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/agent")
public class PingController {

    private final ChatClient chat;

    public PingController(ChatClient chat) {
        this.chat = chat;
    }

    @GetMapping("/ping")
    public String ping(@RequestParam(defaultValue = "Reply with exactly: pong") String prompt) {
        return chat.prompt().user(prompt).call().content();
    }
}
```

- [ ] **Step 6: Run app and hit ping**

```bash
cd /d/programming/hmdp
export DASHSCOPE_API_KEY=sk-real-key-here
mvn -pl agent spring-boot:run
```

In another terminal:

```bash
curl 'http://localhost:8081/agent/ping'
```

Expected: a string containing "pong" (model may add elaboration).

If 401/403, key is wrong. If timeout, network. If 4xx other, model name mismatch — try `qwen-turbo` instead of `qwen-plus`.

- [ ] **Step 7: Commit**

```bash
cd /d/programming/hmdp
git add agent/
git add core/src/main/resources/application.yml   # if profiles include was added
git commit -m "feat(agent): wire Spring AI Alibaba ChatClient and add /agent/ping smoke test"
```

---

## Phase 5: `@AgentTool` annotation and Tool Registry skeleton

**Goal:** Register methods annotated `@AgentTool` into an in-memory registry at startup. TDD.

### Task 5.1: Define the annotation and ToolMeta record

**Files:**
- Create: `agent/src/main/java/com/citycopilot/agent/annotation/AgentTool.java`
- Create: `agent/src/main/java/com/citycopilot/agent/registry/ToolMeta.java`

- [ ] **Step 1: Write the annotation**

Create `agent/src/main/java/com/citycopilot/agent/annotation/AgentTool.java`:

```java
package com.citycopilot.agent.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AgentTool {
    String name();
    String description();
    String[] requiredPermissions() default {};
    int rateLimitPerMinute() default 60;
    boolean idempotent() default true;
}
```

- [ ] **Step 2: Write the metadata record**

Create `agent/src/main/java/com/citycopilot/agent/registry/ToolMeta.java`:

```java
package com.citycopilot.agent.registry;

import java.lang.reflect.Method;

public record ToolMeta(
        String name,
        String description,
        String[] requiredPermissions,
        int rateLimitPerMinute,
        boolean idempotent,
        Object bean,
        Method method,
        String jsonSchema
) { }
```

### Task 5.2: ToolRegistry — TDD

**Files:**
- Create: `agent/src/test/java/com/citycopilot/agent/registry/ToolRegistryTest.java`
- Create: `agent/src/main/java/com/citycopilot/agent/registry/ToolRegistry.java`

- [ ] **Step 1: Write failing test**

Create `agent/src/test/java/com/citycopilot/agent/registry/ToolRegistryTest.java`:

```java
package com.citycopilot.agent.registry;

import com.citycopilot.agent.annotation.AgentTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ToolRegistryTest {

    static class SampleBean {
        @AgentTool(name = "echo", description = "Echo input")
        public String echo(String s) { return s; }

        @AgentTool(name = "add", description = "Add two ints", rateLimitPerMinute = 30, idempotent = true)
        public int add(int a, int b) { return a + b; }

        public String notATool(String s) { return s; }
    }

    @Test
    void registers_only_methods_annotated_with_AgentTool() {
        SampleBean bean = new SampleBean();
        ToolRegistry registry = new ToolRegistry();
        registry.scan(List.of(bean));

        assertThat(registry.names()).containsExactlyInAnyOrder("echo", "add");
    }

    @Test
    void exposes_meta_by_name() {
        SampleBean bean = new SampleBean();
        ToolRegistry registry = new ToolRegistry();
        registry.scan(List.of(bean));

        Optional<ToolMeta> echo = registry.find("echo");
        assertThat(echo).isPresent();
        assertThat(echo.get().description()).isEqualTo("Echo input");
        assertThat(echo.get().rateLimitPerMinute()).isEqualTo(60);

        Optional<ToolMeta> add = registry.find("add");
        assertThat(add).isPresent();
        assertThat(add.get().rateLimitPerMinute()).isEqualTo(30);
    }

    @Test
    void rejects_duplicate_names() {
        class Dup {
            @AgentTool(name = "x", description = "first") public void a() { }
            @AgentTool(name = "x", description = "second") public void b() { }
        }
        ToolRegistry registry = new ToolRegistry();
        assertThat(
                org.junit.jupiter.api.Assertions.assertThrows(
                        IllegalStateException.class,
                        () -> registry.scan(List.of(new Dup()))))
                .hasMessageContaining("duplicate");
    }
}
```

- [ ] **Step 2: Run the test, expect compile + test failure**

```bash
cd /d/programming/hmdp
mvn -q -pl agent test -Dtest=ToolRegistryTest
```

Expected: COMPILATION ERROR (ToolRegistry doesn't exist yet).

- [ ] **Step 3: Implement ToolRegistry**

Create `agent/src/main/java/com/citycopilot/agent/registry/ToolRegistry.java`:

```java
package com.citycopilot.agent.registry;

import com.citycopilot.agent.annotation.AgentTool;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.*;

@Component
public class ToolRegistry {

    private final Map<String, ToolMeta> byName = new LinkedHashMap<>();

    public void scan(Collection<Object> beans) {
        byName.clear();
        for (Object bean : beans) {
            for (Method m : bean.getClass().getDeclaredMethods()) {
                AgentTool ann = m.getAnnotation(AgentTool.class);
                if (ann == null) continue;
                String name = ann.name();
                if (byName.containsKey(name)) {
                    throw new IllegalStateException("duplicate @AgentTool name: " + name);
                }
                m.setAccessible(true);
                byName.put(name, new ToolMeta(
                        name,
                        ann.description(),
                        ann.requiredPermissions(),
                        ann.rateLimitPerMinute(),
                        ann.idempotent(),
                        bean,
                        m,
                        ""   // schema filled in Phase 6
                ));
            }
        }
    }

    public Set<String> names() { return byName.keySet(); }
    public Optional<ToolMeta> find(String name) { return Optional.ofNullable(byName.get(name)); }
    public Collection<ToolMeta> all() { return byName.values(); }
}
```

- [ ] **Step 4: Run tests, expect pass**

```bash
mvn -q -pl agent test -Dtest=ToolRegistryTest
```

Expected: 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/java/com/citycopilot/agent/annotation/ agent/src/main/java/com/citycopilot/agent/registry/ agent/src/test/java/com/citycopilot/agent/registry/
git commit -m "feat(agent): @AgentTool annotation and ToolRegistry with scan/find"
```

### Task 5.3: Spring-driven auto-scan

**Files:**
- Modify: `agent/src/main/java/com/citycopilot/agent/registry/ToolRegistry.java`
- Create: `agent/src/main/java/com/citycopilot/agent/registry/ToolRegistryBootstrap.java`

- [ ] **Step 1: Make the registry initialize from all Spring beans on startup**

Create `agent/src/main/java/com/citycopilot/agent/registry/ToolRegistryBootstrap.java`:

```java
package com.citycopilot.agent.registry;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class ToolRegistryBootstrap implements BeanPostProcessor {

    private final List<Object> candidates = new ArrayList<>();
    private final ToolRegistry registry;

    public ToolRegistryBootstrap(ToolRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (hasAgentToolMethod(bean)) {
            candidates.add(bean);
        }
        return bean;
    }

    private boolean hasAgentToolMethod(Object bean) {
        for (var m : bean.getClass().getDeclaredMethods()) {
            if (m.isAnnotationPresent(com.citycopilot.agent.annotation.AgentTool.class)) return true;
        }
        return false;
    }

    @jakarta.annotation.PostConstruct
    void postReadyHook() {
        // Will be re-called explicitly by an ApplicationListener once context is ready
    }

    public void initialize() {
        registry.scan(candidates);
        log.info("ToolRegistry initialized with {} tools: {}", registry.names().size(), registry.names());
    }
}
```

Create `agent/src/main/java/com/citycopilot/agent/registry/ToolRegistryInitializer.java`:

```java
package com.citycopilot.agent.registry;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class ToolRegistryInitializer {

    private final ToolRegistryBootstrap bootstrap;

    public ToolRegistryInitializer(ToolRegistryBootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        bootstrap.initialize();
    }
}
```

- [ ] **Step 2: Run aggregate build, confirm no regression**

```bash
mvn -q -pl agent clean test
```

Expected: BUILD SUCCESS, prior tests still pass.

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/java/com/citycopilot/agent/registry/
git commit -m "feat(agent): scan Spring beans for @AgentTool methods on application ready"
```

---

## Phase 6: JSON Schema generator for tool parameters

**Goal:** Generate a JSON Schema for each `@AgentTool` method's parameters. Used by LLM Function Calling and MCP tools/list.

### Task 6.1: Schema generation — TDD

**Files:**
- Create: `agent/src/test/java/com/citycopilot/agent/schema/JsonSchemaGeneratorTest.java`
- Create: `agent/src/main/java/com/citycopilot/agent/schema/JsonSchemaGenerator.java`
- Modify: `agent/pom.xml` (add jackson-module-jsonSchema or similar)

- [ ] **Step 1: Add Jackson JSON Schema dep**

In `agent/pom.xml`:

```xml
<dependency>
    <groupId>com.github.victools</groupId>
    <artifactId>jsonschema-generator</artifactId>
    <version>4.36.0</version>
</dependency>
<dependency>
    <groupId>com.github.victools</groupId>
    <artifactId>jsonschema-module-jackson</artifactId>
    <version>4.36.0</version>
</dependency>
```

- [ ] **Step 2: Write failing test**

Create `agent/src/test/java/com/citycopilot/agent/schema/JsonSchemaGeneratorTest.java`:

```java
package com.citycopilot.agent.schema;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class JsonSchemaGeneratorTest {

    public static class Args {
        public String type;
        public Integer maxPrice;
        public Boolean hasVoucher;
    }

    public static class Sample {
        public String search(Args args) { return ""; }
        public int addInts(int a, int b) { return a + b; }
    }

    @Test
    void object_param_yields_object_schema() throws Exception {
        Method m = Sample.class.getMethod("search", Args.class);
        String schema = new JsonSchemaGenerator().forMethod(m);

        assertThat(schema).contains("\"type\":\"object\"");
        assertThat(schema).contains("\"type\":\"object\"");
        assertThat(schema).contains("type");
        assertThat(schema).contains("maxPrice");
        assertThat(schema).contains("hasVoucher");
    }

    @Test
    void primitive_params_yield_flat_object_schema() throws Exception {
        Method m = Sample.class.getMethod("addInts", int.class, int.class);
        String schema = new JsonSchemaGenerator().forMethod(m);

        assertThat(schema).contains("\"type\":\"object\"");
        assertThat(schema).contains("\"arg0\"");
        assertThat(schema).contains("\"arg1\"");
        assertThat(schema).contains("\"type\":\"integer\"");
    }
}
```

- [ ] **Step 3: Run test, expect compile failure**

```bash
mvn -q -pl agent test -Dtest=JsonSchemaGeneratorTest
```

- [ ] **Step 4: Implement schema generator**

Create `agent/src/main/java/com/citycopilot/agent/schema/JsonSchemaGenerator.java`:

```java
package com.citycopilot.agent.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfig;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

@Component
public class JsonSchemaGenerator {

    private final SchemaGenerator generator;
    private final ObjectMapper mapper = new ObjectMapper();

    public JsonSchemaGenerator() {
        SchemaGeneratorConfig cfg = new SchemaGeneratorConfigBuilder(
                SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON).build();
        this.generator = new SchemaGenerator(cfg);
    }

    public String forMethod(Method m) {
        Parameter[] params = m.getParameters();
        // Convention: if there's exactly one non-primitive object param, use its schema directly.
        if (params.length == 1 && !params[0].getType().isPrimitive() && !params[0].getType().getName().startsWith("java.lang.")) {
            JsonNode node = generator.generateSchema(params[0].getType());
            return node.toString();
        }
        // Otherwise build an object schema with arg0, arg1, ...
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        for (int i = 0; i < params.length; i++) {
            JsonNode paramSchema = generator.generateSchema(params[i].getType());
            props.set("arg" + i, paramSchema);
        }
        return root.toString();
    }
}
```

- [ ] **Step 5: Run tests, expect pass**

```bash
mvn -q -pl agent test -Dtest=JsonSchemaGeneratorTest
```

- [ ] **Step 6: Wire schema generation into ToolRegistry.scan**

Edit `ToolRegistry.scan` to call `new JsonSchemaGenerator().forMethod(m)` and store the result in `ToolMeta.jsonSchema`. Update prior tests to assert non-empty schema.

In `ToolRegistry`:

```java
@Component
public class ToolRegistry {

    private final JsonSchemaGenerator schemaGenerator;
    private final Map<String, ToolMeta> byName = new LinkedHashMap<>();

    public ToolRegistry(JsonSchemaGenerator schemaGenerator) {
        this.schemaGenerator = schemaGenerator;
    }

    public void scan(Collection<Object> beans) {
        byName.clear();
        for (Object bean : beans) {
            for (Method m : bean.getClass().getDeclaredMethods()) {
                AgentTool ann = m.getAnnotation(AgentTool.class);
                if (ann == null) continue;
                String name = ann.name();
                if (byName.containsKey(name)) {
                    throw new IllegalStateException("duplicate @AgentTool name: " + name);
                }
                m.setAccessible(true);
                byName.put(name, new ToolMeta(
                        name, ann.description(), ann.requiredPermissions(),
                        ann.rateLimitPerMinute(), ann.idempotent(), bean, m,
                        schemaGenerator.forMethod(m)));
            }
        }
    }
    // ... rest unchanged
}
```

- [ ] **Step 7: Update earlier ToolRegistryTest to construct with the generator**

In `ToolRegistryTest`, replace `new ToolRegistry()` with `new ToolRegistry(new JsonSchemaGenerator())`. Run all tests.

```bash
mvn -q -pl agent test
```

Expected: all green.

- [ ] **Step 8: Commit**

```bash
git add agent/pom.xml agent/src/main/java/com/citycopilot/agent/schema/ agent/src/test/java/com/citycopilot/agent/schema/ agent/src/main/java/com/citycopilot/agent/registry/ToolRegistry.java agent/src/test/java/com/citycopilot/agent/registry/ToolRegistryTest.java
git commit -m "feat(agent): JSON Schema generation for tool params, wired into ToolRegistry"
```

---

## Phase 7: ToolGateway — invocation with validation, permissions, errors, audit

**Goal:** Single entry point `ToolGateway.invoke(name, argsJson, ctx)` for all tool calls (from both Agent and MCP server). Handles arg parsing, JSON Schema validation, permission check (reusing core's `PermissionPolicy`), call dispatch, error normalization, and async trace write. TDD.

### Task 7.1: ToolError, ToolCallContext, ToolResult records

**Files:**
- Create: `agent/src/main/java/com/citycopilot/agent/gateway/ToolError.java`
- Create: `agent/src/main/java/com/citycopilot/agent/gateway/ToolCallContext.java`
- Create: `agent/src/main/java/com/citycopilot/agent/gateway/ToolResult.java`

- [ ] **Step 1: Write the records**

```java
// ToolError.java
package com.citycopilot.agent.gateway;

public record ToolError(String code, String message, boolean retryable) {
    public static ToolError validation(String m) { return new ToolError("VALIDATION", m, false); }
    public static ToolError permission(String m) { return new ToolError("PERMISSION", m, false); }
    public static ToolError notFound(String m) { return new ToolError("NOT_FOUND", m, false); }
    public static ToolError business(String m) { return new ToolError("BUSINESS", m, false); }
    public static ToolError timeout() { return new ToolError("TIMEOUT", "tool call timed out", true); }
    public static ToolError internal(String m) { return new ToolError("INTERNAL", m, true); }
}

// ToolCallContext.java
package com.citycopilot.agent.gateway;

import java.util.Set;

public record ToolCallContext(
        Long userId,
        Set<String> userPermissions,
        String conversationId,
        String traceId
) { }

// ToolResult.java
package com.citycopilot.agent.gateway;

public record ToolResult(boolean ok, Object value, ToolError error) {
    public static ToolResult success(Object v) { return new ToolResult(true, v, null); }
    public static ToolResult failure(ToolError e) { return new ToolResult(false, null, e); }
}
```

### Task 7.2: ToolGateway happy path — TDD

**Files:**
- Create: `agent/src/test/java/com/citycopilot/agent/gateway/ToolGatewayTest.java`
- Create: `agent/src/main/java/com/citycopilot/agent/gateway/ToolGateway.java`

- [ ] **Step 1: Write failing test**

```java
package com.citycopilot.agent.gateway;

import com.citycopilot.agent.annotation.AgentTool;
import com.citycopilot.agent.registry.ToolRegistry;
import com.citycopilot.agent.schema.JsonSchemaGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ToolGatewayTest {

    public static class Args { public String text; }

    public static class Bean {
        @AgentTool(name = "echo", description = "echo")
        public String echo(Args a) { return "echoed: " + a.text; }
    }

    private ToolGateway buildGateway() {
        ToolRegistry reg = new ToolRegistry(new JsonSchemaGenerator());
        reg.scan(List.of(new Bean()));
        return new ToolGateway(reg);
    }

    @Test
    void invokes_tool_with_object_arg() {
        ToolGateway g = buildGateway();
        ToolResult r = g.invoke("echo", "{\"text\":\"hello\"}",
                new ToolCallContext(1L, Set.of(), "c1", "t1"));
        assertThat(r.ok()).isTrue();
        assertThat(r.value()).isEqualTo("echoed: hello");
    }

    @Test
    void returns_not_found_for_unknown_tool() {
        ToolGateway g = buildGateway();
        ToolResult r = g.invoke("nope", "{}",
                new ToolCallContext(1L, Set.of(), "c1", "t1"));
        assertThat(r.ok()).isFalse();
        assertThat(r.error().code()).isEqualTo("NOT_FOUND");
    }

    @Test
    void returns_validation_for_invalid_json() {
        ToolGateway g = buildGateway();
        ToolResult r = g.invoke("echo", "{not-json",
                new ToolCallContext(1L, Set.of(), "c1", "t1"));
        assertThat(r.ok()).isFalse();
        assertThat(r.error().code()).isEqualTo("VALIDATION");
    }
}
```

- [ ] **Step 2: Run, expect compile failure**

- [ ] **Step 3: Implement ToolGateway**

```java
package com.citycopilot.agent.gateway;

import com.citycopilot.agent.registry.ToolMeta;
import com.citycopilot.agent.registry.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Component
public class ToolGateway {

    private final ToolRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();

    public ToolGateway(ToolRegistry registry) {
        this.registry = registry;
    }

    public ToolResult invoke(String name, String argsJson, ToolCallContext ctx) {
        Optional<ToolMeta> metaOpt = registry.find(name);
        if (metaOpt.isEmpty()) return ToolResult.failure(ToolError.notFound("tool not registered: " + name));
        ToolMeta meta = metaOpt.get();

        // permission check
        for (String required : meta.requiredPermissions()) {
            if (!ctx.userPermissions().contains(required)) {
                return ToolResult.failure(ToolError.permission("missing permission: " + required));
            }
        }

        // arg parsing
        Object[] args;
        try {
            args = parseArgs(meta.method(), argsJson);
        } catch (Exception e) {
            return ToolResult.failure(ToolError.validation(e.getMessage()));
        }

        // invocation
        try {
            Object result = meta.method().invoke(meta.bean(), args);
            return ToolResult.success(result);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            log.warn("Tool {} threw {}", name, cause.getClass().getSimpleName(), cause);
            return ToolResult.failure(ToolError.business(cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()));
        } catch (Exception e) {
            log.error("Tool {} internal error", name, e);
            return ToolResult.failure(ToolError.internal(e.getMessage()));
        }
    }

    private Object[] parseArgs(Method method, String json) throws Exception {
        Parameter[] params = method.getParameters();
        if (params.length == 0) return new Object[0];
        if (params.length == 1 && !params[0].getType().isPrimitive() && !params[0].getType().getName().startsWith("java.lang.")) {
            return new Object[] { mapper.readValue(json, params[0].getType()) };
        }
        var node = mapper.readTree(json);
        Object[] out = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            String key = "arg" + i;
            out[i] = mapper.treeToValue(node.get(key), params[i].getType());
        }
        return out;
    }
}
```

- [ ] **Step 4: Run tests, expect pass**

```bash
mvn -q -pl agent test -Dtest=ToolGatewayTest
```

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/java/com/citycopilot/agent/gateway/ agent/src/test/java/com/citycopilot/agent/gateway/
git commit -m "feat(agent): ToolGateway with arg parsing, permission check, error normalization"
```

### Task 7.3: Add JSON Schema validation step

**Files:**
- Modify: `agent/pom.xml` (add networknt json-schema-validator)
- Modify: `agent/src/main/java/com/citycopilot/agent/gateway/ToolGateway.java`
- Modify: `agent/src/test/java/com/citycopilot/agent/gateway/ToolGatewayTest.java`

- [ ] **Step 1: Add validator dep to `agent/pom.xml`**

```xml
<dependency>
    <groupId>com.networknt</groupId>
    <artifactId>json-schema-validator</artifactId>
    <version>1.4.0</version>
</dependency>
```

- [ ] **Step 2: Write failing test asserting schema rejection**

Add to `ToolGatewayTest`:

```java
@Test
void rejects_arg_missing_required_field_per_schema() {
    // Build a tool whose schema requires text:string; pass a number instead.
    ToolGateway g = buildGateway();
    ToolResult r = g.invoke("echo", "{\"text\":123}",
            new ToolCallContext(1L, Set.of(), "c1", "t1"));
    // Jackson would still parse 123 -> String "123" by default. To make this meaningful,
    // assert the failure-mode of schema-validation when a schema-disallowed type appears.
    // Update behaviour: gateway runs schema-validation before parsing.
    // For now, assert a clearly-broken case: an array where object expected.
    ToolResult r2 = g.invoke("echo", "[]",
            new ToolCallContext(1L, Set.of(), "c1", "t1"));
    assertThat(r2.ok()).isFalse();
    assertThat(r2.error().code()).isEqualTo("VALIDATION");
}
```

- [ ] **Step 3: Add schema validation pre-step in `ToolGateway.invoke`**

In `ToolGateway`, before `parseArgs`, add:

```java
try {
    JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    JsonSchema schema = factory.getSchema(meta.jsonSchema());
    Set<ValidationMessage> errors = schema.validate(mapper.readTree(argsJson));
    if (!errors.isEmpty()) {
        return ToolResult.failure(ToolError.validation(errors.iterator().next().getMessage()));
    }
} catch (Exception e) {
    return ToolResult.failure(ToolError.validation(e.getMessage()));
}
```

Add imports: `com.networknt.schema.JsonSchema`, `JsonSchemaFactory`, `SpecVersion`, `ValidationMessage`.

- [ ] **Step 4: Run tests**

```bash
mvn -q -pl agent test -Dtest=ToolGatewayTest
```

Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add agent/pom.xml agent/src/main/java/com/citycopilot/agent/gateway/ToolGateway.java agent/src/test/java/com/citycopilot/agent/gateway/ToolGatewayTest.java
git commit -m "feat(agent): JSON Schema validation in ToolGateway before invocation"
```

---

## Phase 8: Annotate first 5 business methods as @AgentTool

**Goal:** Without changing business logic, expose 5 read-only methods as agent tools for v0.1.

### Task 8.1: Identify and annotate

**Files (modify):**
- `core/src/main/java/com/citycopilot/core/service/impl/ShopServiceImpl.java`
- `core/src/main/java/com/citycopilot/core/service/impl/ShopTypeServiceImpl.java`
- `core/src/main/java/com/citycopilot/core/service/impl/VoucherServiceImpl.java`
- `core/src/main/java/com/citycopilot/core/service/impl/VoucherOrderServiceImpl.java`

- [ ] **Step 1: Add `agent` dep visibility in `core` for the annotation**

In `core/pom.xml` add:

```xml
<dependency>
    <groupId>com.citycopilot</groupId>
    <artifactId>agent</artifactId>
    <version>${project.version}</version>
    <optional>true</optional>
</dependency>
```

**Note:** this creates a cyclic dep (`agent` already depends on `core`). To avoid this, instead extract just `@AgentTool` annotation + `ToolMeta` record into a new tiny module `agent-api` that has no dependencies, and have both `core` and `agent` depend on `agent-api`. Or simpler: move the annotation to `core/src/main/java/com/citycopilot/core/agent/AgentTool.java` and have `agent` module reference it from there.

Choose the simpler path: move annotation to `core`.

```bash
cd /d/programming/hmdp
mkdir -p core/src/main/java/com/citycopilot/core/agent
git mv agent/src/main/java/com/citycopilot/agent/annotation/AgentTool.java core/src/main/java/com/citycopilot/core/agent/AgentTool.java
sed -i 's|package com.citycopilot.agent.annotation;|package com.citycopilot.core.agent;|' core/src/main/java/com/citycopilot/core/agent/AgentTool.java
# Update import-line usages
find agent/src -type f -name "*.java" -exec sed -i 's|import com.citycopilot.agent.annotation.AgentTool;|import com.citycopilot.core.agent.AgentTool;|g' {} \;
# Update fully-qualified inline usages (e.g. inside ToolRegistryBootstrap)
find agent/src -type f -name "*.java" -exec sed -i 's|com\.citycopilot\.agent\.annotation\.AgentTool|com.citycopilot.core.agent.AgentTool|g' {} \;
```

Re-run tests:

```bash
mvn -q -pl agent test
```

Expected: green.

- [ ] **Step 2: Annotate `ShopServiceImpl.queryById`**

Find the method signature in `ShopServiceImpl` (likely `public Result queryById(Long id)`). Above it, add:

```java
@com.citycopilot.core.agent.AgentTool(
    name = "get_shop_detail",
    description = "Look up a shop by ID. Returns shop name, type, address, score, average price, and verified coordinates if available."
)
public Result queryById(Long id) { /* existing body */ }
```

- [ ] **Step 3: Annotate `ShopServiceImpl.queryShopByType` (and/or searchNearby if it exists)**

```java
@AgentTool(
    name = "search_shops_by_type",
    description = "Search shops by type ID, optionally sorted by distance if user location (x,y) is provided. Returns a paginated list."
)
public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) { /* existing */ }
```

If there is a separate `nearbyShop` method on the same service for the new `/shop/nearby` endpoint, also annotate it:

```java
@AgentTool(
    name = "search_nearby_shops",
    description = "List shops near a given location (x,y) regardless of type, sorted by distance."
)
public Result nearbyShops(Double x, Double y, Integer current) { /* existing */ }
```

- [ ] **Step 4: Annotate `ShopTypeServiceImpl.queryTypeList`**

```java
@AgentTool(
    name = "list_shop_types",
    description = "List all available shop categories (e.g. 美食, KTV, 健身运动)."
)
public Result queryTypeList() { /* existing */ }
```

- [ ] **Step 5: Annotate one read on `VoucherServiceImpl` and one read on `VoucherOrderServiceImpl`**

For VoucherService — typically `queryVoucherOfShop(Long shopId)`:

```java
@AgentTool(
    name = "list_vouchers_by_shop",
    description = "List active vouchers (regular + flash-sale) for a given shop. Returns ID, title, value, time window, and stock for flash-sale vouchers."
)
public Result queryVoucherOfShop(Long shopId) { /* existing */ }
```

For VoucherOrderService:

```java
@AgentTool(
    name = "get_my_orders",
    description = "List the current logged-in user's orders, newest first."
)
public Result listOfCurrentUser() { /* existing */ }
```

- [ ] **Step 6: Run aggregate test**

```bash
cd /d/programming/hmdp
mvn -q clean test
```

Expected: all green; on startup of `agent` module (if run via `agent` main), `ToolRegistry` log line should list the 5 tools.

- [ ] **Step 7: Start and verify**

```bash
export DASHSCOPE_API_KEY=sk-...
mvn -pl agent spring-boot:run -pl agent
```

In another terminal:

```bash
# Tools are not yet exposed via HTTP; verify via log line "ToolRegistry initialized with 5 tools"
```

Expected: log line shows `ToolRegistry initialized with 5 tools: [get_shop_detail, search_shops_by_type, search_nearby_shops, list_shop_types, list_vouchers_by_shop, get_my_orders]` (count may be 5 or 6 depending on whether nearbyShops existed).

- [ ] **Step 8: Commit**

```bash
git add core/ agent/
git commit -m "feat(core): expose 5 read-only business methods as @AgentTool"
```

---

## Phase 9: LLM Client wrapper + Spring AI Function Calling integration

**Goal:** Build a thin wrapper that, given a user message and the current `ToolRegistry`, calls Spring AI `ChatClient` with all tools as `FunctionCallback`s. This lets the LLM choose tools natively.

### Task 9.1: Spring AI FunctionCallback bridge

**Files:**
- Create: `agent/src/main/java/com/citycopilot/agent/llm/ToolRegistryFunctionCallbackProvider.java`
- Create: `agent/src/main/java/com/citycopilot/agent/llm/AgentChatService.java`

- [ ] **Step 1: Build a provider that converts `ToolMeta` to `FunctionCallback`**

Create `agent/src/main/java/com/citycopilot/agent/llm/ToolRegistryFunctionCallbackProvider.java`:

```java
package com.citycopilot.agent.llm;

import com.citycopilot.agent.gateway.ToolCallContext;
import com.citycopilot.agent.gateway.ToolGateway;
import com.citycopilot.agent.gateway.ToolResult;
import com.citycopilot.agent.registry.ToolMeta;
import com.citycopilot.agent.registry.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.model.function.FunctionCallbackWrapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

@Component
public class ToolRegistryFunctionCallbackProvider {

    private final ToolRegistry registry;
    private final ToolGateway gateway;
    private final ObjectMapper mapper = new ObjectMapper();

    public ToolRegistryFunctionCallbackProvider(ToolRegistry registry, ToolGateway gateway) {
        this.registry = registry;
        this.gateway = gateway;
    }

    public List<FunctionCallback> callbacksFor(ToolCallContext ctx) {
        List<FunctionCallback> out = new ArrayList<>();
        for (ToolMeta meta : registry.all()) {
            Function<String, String> fn = argsJson -> {
                ToolResult res = gateway.invoke(meta.name(), argsJson, ctx);
                try {
                    return mapper.writeValueAsString(res);
                } catch (Exception e) {
                    return "{\"ok\":false,\"error\":\"" + e.getMessage() + "\"}";
                }
            };
            out.add(FunctionCallbackWrapper.builder(fn)
                    .withName(meta.name())
                    .withDescription(meta.description())
                    .withInputType(String.class)
                    .build());
        }
        return out;
    }
}
```

**Note on Spring AI version:** the `FunctionCallbackWrapper.builder` API shifted between Spring AI milestones. If compile fails, the equivalent in current Spring AI 1.0.0-M6 is `FunctionCallback.builder().description(...).inputType(...).function(fn).build()`. Run `mvn dependency:tree -pl agent` to confirm version and adjust.

- [ ] **Step 2: Wire AgentChatService that uses the provider**

Create `agent/src/main/java/com/citycopilot/agent/llm/AgentChatService.java`:

```java
package com.citycopilot.agent.llm;

import com.citycopilot.agent.gateway.ToolCallContext;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class AgentChatService {

    private final ChatClient chat;
    private final ToolRegistryFunctionCallbackProvider provider;

    public AgentChatService(ChatClient chat, ToolRegistryFunctionCallbackProvider provider) {
        this.chat = chat;
        this.provider = provider;
    }

    public String singleShot(String userMessage, ToolCallContext ctx) {
        return chat.prompt()
                .user(userMessage)
                .functions(provider.callbacksFor(ctx).toArray(new org.springframework.ai.model.function.FunctionCallback[0]))
                .call()
                .content();
    }
}
```

- [ ] **Step 3: Hook into a temporary smoke endpoint to verify the LLM chooses a tool**

Modify `agent/src/main/java/com/citycopilot/agent/sse/PingController.java`:

```java
@RestController
@RequestMapping("/agent")
public class PingController {

    private final ChatClient chat;
    private final AgentChatService agentChat;

    public PingController(ChatClient chat, AgentChatService agentChat) {
        this.chat = chat;
        this.agentChat = agentChat;
    }

    @GetMapping("/ping")
    public String ping(@RequestParam(defaultValue = "Reply with exactly: pong") String prompt) {
        return chat.prompt().user(prompt).call().content();
    }

    @GetMapping("/smoke-tool")
    public String smokeTool() {
        return agentChat.singleShot(
                "List the available shop categories. Use the list_shop_types tool.",
                new com.citycopilot.agent.gateway.ToolCallContext(1L, java.util.Set.of(), "smoke", "smoke-1"));
    }
}
```

- [ ] **Step 4: Run and verify**

```bash
export DASHSCOPE_API_KEY=sk-...
mvn -pl agent spring-boot:run -pl agent
```

```bash
curl 'http://localhost:8081/agent/smoke-tool'
```

Expected: a response containing shop categories from the database (or empty list if DB has no types). If response is "I cannot...", check log for `ToolGateway` invocation — if the gateway was not called, the LLM did not pick the tool, indicating either model didn't support function calling or callbacks were not registered.

- [ ] **Step 5: Commit**

```bash
git add agent/
git commit -m "feat(agent): FunctionCallback provider + AgentChatService smoke endpoint"
```

---

## Phase 10: ReAct Executor — TDD

**Goal:** Implement the ReAct loop explicitly (not relying on Spring AI's built-in tool-calling loop) so we control max-steps, anti-loop, token budget, observation injection.

### Task 10.1: Executor interface + ReAct implementation

**Files:**
- Create: `agent/src/main/java/com/citycopilot/agent/orchestrator/Executor.java`
- Create: `agent/src/main/java/com/citycopilot/agent/orchestrator/AgentRequest.java`
- Create: `agent/src/main/java/com/citycopilot/agent/orchestrator/AgentResponse.java`
- Create: `agent/src/main/java/com/citycopilot/agent/orchestrator/AgentStep.java`
- Create: `agent/src/main/java/com/citycopilot/agent/orchestrator/ReactExecutor.java`
- Create: `agent/src/test/java/com/citycopilot/agent/orchestrator/ReactExecutorTest.java`

- [ ] **Step 1: Define the data types**

```java
// AgentRequest.java
package com.citycopilot.agent.orchestrator;

import com.citycopilot.agent.gateway.ToolCallContext;

public record AgentRequest(String userMessage, ToolCallContext ctx) { }

// AgentStep.java
package com.citycopilot.agent.orchestrator;

public sealed interface AgentStep {
    record Thought(String content) implements AgentStep { }
    record ToolCall(String tool, String argsJson) implements AgentStep { }
    record Observation(String tool, String resultJson, boolean ok) implements AgentStep { }
    record FinalAnswer(String content) implements AgentStep { }
}

// AgentResponse.java
package com.citycopilot.agent.orchestrator;

import java.util.List;

public record AgentResponse(
        String finalAnswer,
        List<AgentStep> steps,
        Mode mode,
        boolean truncated
) { }

// Mode.java
package com.citycopilot.agent.orchestrator;

public enum Mode { REACT, PLAN_EXECUTE }

// Executor.java
package com.citycopilot.agent.orchestrator;

public interface Executor {
    AgentResponse run(AgentRequest req);
}
```

- [ ] **Step 2: Write the failing test using a fake LLM**

```java
package com.citycopilot.agent.orchestrator;

import com.citycopilot.agent.gateway.ToolCallContext;
import com.citycopilot.agent.gateway.ToolGateway;
import com.citycopilot.agent.gateway.ToolResult;
import com.citycopilot.agent.llm.FakeLlm;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class ReactExecutorTest {

    @Test
    void single_tool_call_then_final_answer() {
        FakeLlm llm = new FakeLlm(List.of(
                "{\"tool\":\"list_shop_types\",\"args\":{}}",
                "{\"final\":\"Categories: 美食, KTV\"}"
        ));
        ToolGateway gateway = Mockito.mock(ToolGateway.class);
        Mockito.when(gateway.invoke(eq("list_shop_types"), any(), any()))
                .thenReturn(ToolResult.success("[\"美食\",\"KTV\"]"));

        ReactExecutor exec = new ReactExecutor(llm, gateway, 5);
        AgentResponse resp = exec.run(new AgentRequest(
                "List categories",
                new ToolCallContext(1L, Set.of(), "c", "t")));

        assertThat(resp.finalAnswer()).contains("美食");
        assertThat(resp.steps()).hasSize(3);
        assertThat(resp.truncated()).isFalse();
    }

    @Test
    void anti_loop_breaks_on_same_tool_repeated_twice() {
        FakeLlm llm = new FakeLlm(List.of(
                "{\"tool\":\"x\",\"args\":{\"k\":1}}",
                "{\"tool\":\"x\",\"args\":{\"k\":1}}",
                "{\"final\":\"truncated\"}"
        ));
        ToolGateway gateway = Mockito.mock(ToolGateway.class);
        Mockito.when(gateway.invoke(any(), any(), any())).thenReturn(ToolResult.success("ok"));

        ReactExecutor exec = new ReactExecutor(llm, gateway, 5);
        AgentResponse resp = exec.run(new AgentRequest(
                "loop",
                new ToolCallContext(1L, Set.of(), "c", "t")));

        assertThat(resp.truncated()).isTrue();
    }

    @Test
    void max_steps_enforced() {
        FakeLlm llm = new FakeLlm(List.of(
                "{\"tool\":\"a\",\"args\":{}}",
                "{\"tool\":\"b\",\"args\":{}}",
                "{\"tool\":\"c\",\"args\":{}}",
                "{\"tool\":\"d\",\"args\":{}}"
        ));
        ToolGateway gateway = Mockito.mock(ToolGateway.class);
        Mockito.when(gateway.invoke(any(), any(), any())).thenReturn(ToolResult.success("ok"));

        ReactExecutor exec = new ReactExecutor(llm, gateway, 2);
        AgentResponse resp = exec.run(new AgentRequest(
                "many steps",
                new ToolCallContext(1L, Set.of(), "c", "t")));

        assertThat(resp.truncated()).isTrue();
    }
}
```

Also create `FakeLlm` test helper:

```java
// agent/src/test/java/com/citycopilot/agent/llm/FakeLlm.java
package com.citycopilot.agent.llm;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

public class FakeLlm implements LlmClient {
    private final Queue<String> scripted;

    public FakeLlm(List<String> responses) {
        this.scripted = new LinkedList<>(responses);
    }

    @Override
    public String complete(String systemPrompt, List<String> historyJson) {
        if (scripted.isEmpty()) throw new IllegalStateException("FakeLlm out of scripted responses");
        return scripted.poll();
    }
}
```

And the LlmClient interface:

```java
// agent/src/main/java/com/citycopilot/agent/llm/LlmClient.java
package com.citycopilot.agent.llm;

import java.util.List;

public interface LlmClient {
    String complete(String systemPrompt, List<String> historyJson);
}
```

- [ ] **Step 3: Run, expect compile failure**

- [ ] **Step 4: Implement ReactExecutor**

```java
package com.citycopilot.agent.orchestrator;

import com.citycopilot.agent.gateway.ToolCallContext;
import com.citycopilot.agent.gateway.ToolGateway;
import com.citycopilot.agent.gateway.ToolResult;
import com.citycopilot.agent.llm.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
public class ReactExecutor implements Executor {

    private static final String SYSTEM_PROMPT = """
            You are an action-oriented assistant.
            Respond with JSON: either a tool call {"tool":"name","args":{...}}
            or a final answer {"final":"<text>"}.
            Use tools when needed. Do not invent tool names.
            """;

    private final LlmClient llm;
    private final ToolGateway gateway;
    private final int maxSteps;
    private final ObjectMapper mapper = new ObjectMapper();

    public ReactExecutor(LlmClient llm, ToolGateway gateway, int maxSteps) {
        this.llm = llm;
        this.gateway = gateway;
        this.maxSteps = maxSteps;
    }

    @Override
    public AgentResponse run(AgentRequest req) {
        List<AgentStep> steps = new ArrayList<>();
        List<String> history = new ArrayList<>();
        history.add("{\"role\":\"user\",\"content\":" + asJsonString(req.userMessage()) + "}");
        Set<String> seenCalls = new HashSet<>();

        for (int i = 0; i < maxSteps; i++) {
            String raw = llm.complete(SYSTEM_PROMPT, history);
            history.add("{\"role\":\"assistant\",\"content\":" + asJsonString(raw) + "}");
            steps.add(new AgentStep.Thought(raw));
            JsonNode node;
            try { node = mapper.readTree(raw); } catch (Exception e) {
                return new AgentResponse("LLM produced invalid JSON; aborting.", steps, Mode.REACT, true);
            }
            if (node.has("final")) {
                String answer = node.get("final").asText();
                steps.add(new AgentStep.FinalAnswer(answer));
                return new AgentResponse(answer, steps, Mode.REACT, false);
            }
            if (!node.has("tool")) {
                return new AgentResponse(raw, steps, Mode.REACT, true);
            }
            String tool = node.get("tool").asText();
            String args = node.has("args") ? node.get("args").toString() : "{}";
            String dedupKey = tool + "::" + args;
            if (!seenCalls.add(dedupKey)) {
                return new AgentResponse("Stopped: same tool invoked with same args twice.", steps, Mode.REACT, true);
            }
            steps.add(new AgentStep.ToolCall(tool, args));
            ToolResult res = gateway.invoke(tool, args, req.ctx());
            String obs;
            try { obs = mapper.writeValueAsString(res); } catch (Exception e) { obs = "{\"ok\":false}"; }
            steps.add(new AgentStep.Observation(tool, obs, res.ok()));
            history.add("{\"role\":\"tool\",\"name\":" + asJsonString(tool) + ",\"content\":" + asJsonString(obs) + "}");
        }
        return new AgentResponse("Reached max steps.", steps, Mode.REACT, true);
    }

    private static String asJsonString(String s) {
        // minimal escape: rely on Jackson to write the string node
        return new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(s).toString();
    }
}
```

- [ ] **Step 5: Run tests**

```bash
mvn -q -pl agent test -Dtest=ReactExecutorTest
```

Expected: all 3 tests pass.

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/java/com/citycopilot/agent/orchestrator/ agent/src/main/java/com/citycopilot/agent/llm/LlmClient.java agent/src/test/java/com/citycopilot/agent/orchestrator/ agent/src/test/java/com/citycopilot/agent/llm/FakeLlm.java
git commit -m "feat(agent): ReactExecutor with anti-loop, max-steps, JSON tool-call protocol"
```

### Task 10.2: Real LlmClient implementation using Spring AI

**Files:**
- Create: `agent/src/main/java/com/citycopilot/agent/llm/SpringAiLlmClient.java`

- [ ] **Step 1: Implement**

```java
package com.citycopilot.agent.llm;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Primary
@Component
public class SpringAiLlmClient implements LlmClient {

    private final ChatClient chat;

    public SpringAiLlmClient(ChatClient chat) {
        this.chat = chat;
    }

    @Override
    public String complete(String systemPrompt, List<String> historyJson) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        for (String entry : historyJson) {
            messages.add(new UserMessage(entry));
        }
        return chat.prompt(new Prompt(messages)).call().content();
    }
}
```

(Treating history entries as text is a deliberate simplification — the LLM still parses JSON content; for v0.2 we'll switch to proper structured message roles.)

- [ ] **Step 2: Compile**

```bash
mvn -q -pl agent compile
```

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/java/com/citycopilot/agent/llm/SpringAiLlmClient.java
git commit -m "feat(agent): SpringAiLlmClient as primary LlmClient implementation"
```

---

## Phase 11: Trace persistence to Redis

**Goal:** Record every Agent run's steps to Redis under `trace:{traceId}`, retain 7 days, expose `GET /agent/traces/{id}`.

### Task 11.1: TraceWriter and TraceReader

**Files:**
- Create: `agent/src/main/java/com/citycopilot/agent/trace/TraceRecord.java`
- Create: `agent/src/main/java/com/citycopilot/agent/trace/TraceStore.java`
- Create: `agent/src/test/java/com/citycopilot/agent/trace/TraceStoreTest.java`

- [ ] **Step 1: Define record**

```java
// TraceRecord.java
package com.citycopilot.agent.trace;

import com.citycopilot.agent.orchestrator.AgentStep;

import java.time.Instant;
import java.util.List;

public record TraceRecord(
        String traceId,
        Long userId,
        String conversationId,
        String mode,
        Instant startedAt,
        long durationMs,
        boolean truncated,
        String finalAnswer,
        List<AgentStep> steps
) { }
```

- [ ] **Step 2: TDD store**

```java
// TraceStoreTest.java
package com.citycopilot.agent.trace;

import com.citycopilot.agent.orchestrator.AgentStep;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = com.citycopilot.core.CityCopilotApplication.class)
class TraceStoreTest {

    @Autowired
    TraceStore store;

    @Test
    void roundtrip() {
        TraceRecord r = new TraceRecord(
                "t-test-1", 1L, "conv1", "REACT", Instant.now(),
                123, false, "answer",
                List.of(new AgentStep.Thought("hi"), new AgentStep.FinalAnswer("answer")));
        store.write(r);
        Optional<TraceRecord> got = store.read("t-test-1");
        assertThat(got).isPresent();
        assertThat(got.get().finalAnswer()).isEqualTo("answer");
        assertThat(got.get().steps()).hasSize(2);
    }
}
```

- [ ] **Step 3: Implement**

```java
// TraceStore.java
package com.citycopilot.agent.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Slf4j
@Component
public class TraceStore {

    private static final String KEY_PREFIX = "trace:";
    private static final Duration TTL = Duration.ofDays(7);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public TraceStore(StringRedisTemplate redis) {
        this.redis = redis;
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    }

    public void write(TraceRecord r) {
        try {
            redis.opsForValue().set(KEY_PREFIX + r.traceId(), mapper.writeValueAsString(r), TTL);
        } catch (Exception e) {
            log.warn("trace write failed for {}", r.traceId(), e);
        }
    }

    public Optional<TraceRecord> read(String traceId) {
        String v = redis.opsForValue().get(KEY_PREFIX + traceId);
        if (v == null) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(v, TraceRecord.class));
        } catch (Exception e) {
            log.warn("trace read failed for {}", traceId, e);
            return Optional.empty();
        }
    }
}
```

Add to `agent/pom.xml`:

```xml
<dependency>
    <groupId>com.fasterxml.jackson.datatype</groupId>
    <artifactId>jackson-datatype-jsr310</artifactId>
</dependency>
```

- [ ] **Step 4: Run test**

```bash
mvn -q -pl agent test -Dtest=TraceStoreTest
```

Expected: passes (requires Redis running on localhost:6379; if CI/local lacks Redis, mark test with `@EnabledIfEnvironmentVariable` or use Testcontainers — pick Testcontainers later for v0.2; for now require local Redis).

- [ ] **Step 5: Wire ReactExecutor to write traces**

Modify `ReactExecutor` (or wrap it in a `TracingExecutor` decorator):

Create `agent/src/main/java/com/citycopilot/agent/orchestrator/TracingExecutor.java`:

```java
package com.citycopilot.agent.orchestrator;

import com.citycopilot.agent.trace.TraceRecord;
import com.citycopilot.agent.trace.TraceStore;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class TracingExecutor implements Executor {

    private final ReactExecutor inner;
    private final TraceStore store;

    public TracingExecutor(ReactExecutor inner, TraceStore store) {
        this.inner = inner;
        this.store = store;
    }

    @Override
    public AgentResponse run(AgentRequest req) {
        String traceId = req.ctx().traceId() == null ? UUID.randomUUID().toString() : req.ctx().traceId();
        AgentRequest withTrace = new AgentRequest(
                req.userMessage(),
                new com.citycopilot.agent.gateway.ToolCallContext(
                        req.ctx().userId(), req.ctx().userPermissions(),
                        req.ctx().conversationId(), traceId));
        Instant start = Instant.now();
        AgentResponse resp = inner.run(withTrace);
        long dur = java.time.Duration.between(start, Instant.now()).toMillis();
        store.write(new TraceRecord(
                traceId, withTrace.ctx().userId(), withTrace.ctx().conversationId(),
                resp.mode().name(), start, dur, resp.truncated(),
                resp.finalAnswer(), resp.steps()));
        return resp;
    }
}
```

Also expose ReactExecutor as a Spring bean (`@Component` plus a `@Configuration` constructing it with `maxSteps` from properties — easier: make it a `@Component` and inject `@Value("${citycopilot.agent.max-react-steps:5}")` into a constructor).

Modify `ReactExecutor`:

```java
@org.springframework.stereotype.Component
public class ReactExecutor implements Executor {
    public ReactExecutor(LlmClient llm, ToolGateway gateway,
                         @org.springframework.beans.factory.annotation.Value("${citycopilot.agent.max-react-steps:5}") int maxSteps) {
        this.llm = llm;
        this.gateway = gateway;
        this.maxSteps = maxSteps;
    }
    // rest unchanged
}
```

Update `ReactExecutorTest` to construct directly with `new ReactExecutor(llm, gateway, 5)` (unchanged), as the `@Value` only fires when Spring-managed.

- [ ] **Step 6: Commit**

```bash
git add agent/
git commit -m "feat(agent): persist agent runs as TraceRecord in Redis with 7d TTL"
```

---

## Phase 12: SSE AgentController + frontend integration

**Goal:** `POST /agent/chat` accepts user message, returns SSE stream of step events. Frontend renders tool calls as cards.

### Task 12.1: Backend SSE endpoint

**Files:**
- Create: `agent/src/main/java/com/citycopilot/agent/sse/AgentController.java`
- Create: `agent/src/main/java/com/citycopilot/agent/sse/AgentChatRequest.java`
- Create: `agent/src/main/java/com/citycopilot/agent/sse/AgentSseEvent.java`

- [ ] **Step 1: Define request and event types**

```java
// AgentChatRequest.java
package com.citycopilot.agent.sse;

public record AgentChatRequest(String message, String conversationId) { }

// AgentSseEvent.java
package com.citycopilot.agent.sse;

public record AgentSseEvent(String type, Object data) {
    public static AgentSseEvent thought(String s) { return new AgentSseEvent("thought", s); }
    public static AgentSseEvent toolCall(String tool, String args) { return new AgentSseEvent("tool_call", java.util.Map.of("tool", tool, "args", args)); }
    public static AgentSseEvent toolResult(String tool, Object r, boolean ok) { return new AgentSseEvent("tool_result", java.util.Map.of("tool", tool, "result", r, "ok", ok)); }
    public static AgentSseEvent answer(String s) { return new AgentSseEvent("answer", s); }
    public static AgentSseEvent done(String traceId) { return new AgentSseEvent("done", java.util.Map.of("traceId", traceId)); }
    public static AgentSseEvent error(String m) { return new AgentSseEvent("error", m); }
}
```

- [ ] **Step 2: Implement controller**

```java
package com.citycopilot.agent.sse;

import com.citycopilot.agent.gateway.ToolCallContext;
import com.citycopilot.agent.orchestrator.AgentRequest;
import com.citycopilot.agent.orchestrator.AgentResponse;
import com.citycopilot.agent.orchestrator.AgentStep;
import com.citycopilot.agent.orchestrator.Executor;
import com.citycopilot.core.utils.UserHolder;
import com.citycopilot.core.dto.UserDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Set;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/agent")
public class AgentController {

    private final Executor executor;

    public AgentController(Executor executor) {
        this.executor = executor;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody AgentChatRequest req) {
        SseEmitter emitter = new SseEmitter(60_000L);
        UserDTO user = UserHolder.getUser();
        Long userId = user == null ? null : user.getId();
        String traceId = UUID.randomUUID().toString();
        ToolCallContext ctx = new ToolCallContext(userId, Set.of(), req.conversationId(), traceId);

        new Thread(() -> {
            try {
                AgentResponse resp = executor.run(new AgentRequest(req.message(), ctx));
                for (AgentStep step : resp.steps()) {
                    switch (step) {
                        case AgentStep.Thought t -> emitter.send(AgentSseEvent.thought(t.content()));
                        case AgentStep.ToolCall c -> emitter.send(AgentSseEvent.toolCall(c.tool(), c.argsJson()));
                        case AgentStep.Observation o -> emitter.send(AgentSseEvent.toolResult(o.tool(), o.resultJson(), o.ok()));
                        case AgentStep.FinalAnswer a -> emitter.send(AgentSseEvent.answer(a.content()));
                    }
                }
                emitter.send(AgentSseEvent.done(traceId));
                emitter.complete();
            } catch (Exception e) {
                log.error("agent chat error", e);
                try { emitter.send(AgentSseEvent.error(e.getMessage())); emitter.completeWithError(e); }
                catch (Exception ignored) { }
            }
        }, "agent-chat-" + traceId).start();

        return emitter;
    }
}
```

Note: the events are emitted *after* the executor completes (not truly streaming). For v0.1 this is acceptable — the UI still gets event-by-event rendering even though they arrive in a burst. True per-step streaming requires injecting an `AgentEventSink` into the executor. Marked as v0.2 improvement.

- [ ] **Step 3: Wire executor selection (only ReAct for now)**

Mark `TracingExecutor` as `@Primary @Component` so `Executor` injection prefers it.

In `TracingExecutor`:

```java
@org.springframework.context.annotation.Primary
@org.springframework.stereotype.Component
public class TracingExecutor implements Executor { /* ... */ }
```

- [ ] **Step 4: Manual smoke**

```bash
export DASHSCOPE_API_KEY=sk-...
mvn -pl agent spring-boot:run -pl agent
```

```bash
curl -N -H 'Content-Type: application/json' \
    -d '{"message":"List the available shop categories","conversationId":"c1"}' \
    http://localhost:8081/agent/chat
```

Expected: a stream of `data: {...}` lines including a `thought`, possibly a `tool_call`/`tool_result`, an `answer`, and a `done`.

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/java/com/citycopilot/agent/sse/
git commit -m "feat(agent): POST /agent/chat SSE endpoint emitting step events"
```

### Task 12.2: Frontend SSE client

**Files:**
- Create: `frontend/src/composables/agent.ts`
- Create: `frontend/src/views/AgentChatView.vue`
- Modify: `frontend/src/router/index.ts` (add /agent route)

- [ ] **Step 1: SSE composable using `fetch` + ReadableStream (EventSource cannot POST)**

```ts
// frontend/src/composables/agent.ts
export type AgentEvent =
  | { type: 'thought'; data: string }
  | { type: 'tool_call'; data: { tool: string; args: string } }
  | { type: 'tool_result'; data: { tool: string; result: any; ok: boolean } }
  | { type: 'answer'; data: string }
  | { type: 'done'; data: { traceId: string } }
  | { type: 'error'; data: string };

export async function streamAgentChat(
  message: string,
  conversationId: string,
  onEvent: (e: AgentEvent) => void,
  token?: string,
) {
  const res = await fetch('/api/agent/chat', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { authorization: token } : {}),
      Accept: 'text/event-stream',
    },
    body: JSON.stringify({ message, conversationId }),
  });
  if (!res.body) throw new Error('no body');
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buf = '';
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buf += decoder.decode(value, { stream: true });
    let idx;
    while ((idx = buf.indexOf('\n\n')) >= 0) {
      const block = buf.slice(0, idx);
      buf = buf.slice(idx + 2);
      const dataLine = block.split('\n').find(l => l.startsWith('data:'));
      if (!dataLine) continue;
      try {
        const ev = JSON.parse(dataLine.slice(5).trim()) as AgentEvent;
        onEvent(ev);
      } catch (e) { /* skip malformed */ }
    }
  }
}
```

- [ ] **Step 2: Minimal chat view**

```vue
<!-- frontend/src/views/AgentChatView.vue -->
<template>
  <div class="agent-chat">
    <div class="messages">
      <div v-for="(m, i) in messages" :key="i" :class="['msg', m.role]">
        <div v-if="m.role === 'tool'" class="tool-card">
          <strong>{{ m.tool }}</strong>
          <pre>{{ m.content }}</pre>
        </div>
        <div v-else class="text">{{ m.content }}</div>
      </div>
    </div>
    <form @submit.prevent="send">
      <input v-model="input" placeholder="Ask CityCopilot..." />
      <button type="submit" :disabled="busy">Send</button>
    </form>
    <small v-if="traceId">trace: {{ traceId }}</small>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { streamAgentChat } from '../composables/agent';

const input = ref('');
const busy = ref(false);
const messages = ref<Array<{ role: 'user' | 'assistant' | 'tool'; content: string; tool?: string }>>([]);
const traceId = ref<string | null>(null);

async function send() {
  if (!input.value.trim()) return;
  const userMsg = input.value;
  messages.value.push({ role: 'user', content: userMsg });
  input.value = '';
  busy.value = true;
  const conv = traceId.value ?? 'c-' + Date.now();
  await streamAgentChat(userMsg, conv, (ev) => {
    if (ev.type === 'thought') {
      // skip rendering raw thought JSON for now
    } else if (ev.type === 'tool_call') {
      messages.value.push({ role: 'tool', content: ev.data.args, tool: ev.data.tool });
    } else if (ev.type === 'tool_result') {
      messages.value.push({ role: 'tool', content: typeof ev.data.result === 'string' ? ev.data.result : JSON.stringify(ev.data.result), tool: ev.data.tool + (ev.data.ok ? ' ✓' : ' ✗') });
    } else if (ev.type === 'answer') {
      messages.value.push({ role: 'assistant', content: ev.data });
    } else if (ev.type === 'done') {
      traceId.value = ev.data.traceId;
    } else if (ev.type === 'error') {
      messages.value.push({ role: 'assistant', content: 'Error: ' + ev.data });
    }
  });
  busy.value = false;
}
</script>

<style scoped>
.agent-chat { max-width: 720px; margin: 0 auto; padding: 1rem; }
.messages { min-height: 50vh; }
.msg { margin: .5rem 0; }
.msg.user .text { background: #e6f4ff; padding: .5rem 1rem; border-radius: .5rem; }
.msg.assistant .text { background: #f4f4f4; padding: .5rem 1rem; border-radius: .5rem; }
.tool-card { border: 1px solid #ddd; padding: .5rem; border-radius: .5rem; background: #fafafa; }
.tool-card pre { margin: .25rem 0 0; font-size: .85em; white-space: pre-wrap; }
</style>
```

- [ ] **Step 3: Add route**

In `frontend/src/router/index.ts`, add:

```ts
{ path: '/agent', component: () => import('../views/AgentChatView.vue') }
```

- [ ] **Step 4: Manual smoke**

```bash
cd frontend
npm run dev
```

Open `http://localhost:5173/agent`, type "List the shop categories", confirm the response renders with a tool card showing `list_shop_types` call.

- [ ] **Step 5: Commit**

```bash
cd /d/programming/hmdp
git add frontend/src/composables/agent.ts frontend/src/views/AgentChatView.vue frontend/src/router/
git commit -m "feat(frontend): /agent chat view with SSE streaming and tool-call cards"
```

---

**v0.1 MVP IS NOW COMPLETE.** The application can:
- Receive a user message via `/agent/chat`
- Route to LLM with 5+ tools as function callbacks
- Run ReAct loop with anti-loop, max-steps, JSON tool-call protocol
- Stream step events to frontend
- Persist trace to Redis

**Commit checkpoint:**

```bash
cd /d/programming/hmdp
git tag v0.1-mvp
git log --oneline | head -20
```

You can investably stop here and resume Phases 13+ after starting interviews.

---

## Phase 13: Plan-Execute-Replan Executor + Router

**Goal:** Add a second executor that produces a multi-step plan up-front, executes with reference resolution, replans on failure.

### Task 13.1: Plan data model

**Files:**
- Create: `agent/src/main/java/com/citycopilot/agent/orchestrator/Plan.java`
- Create: `agent/src/main/java/com/citycopilot/agent/orchestrator/PlanStep.java`

- [ ] **Step 1: Define**

```java
// PlanStep.java
package com.citycopilot.agent.orchestrator;

import java.util.Map;

public record PlanStep(
        int idx,
        String tool,
        Map<String, Object> args,         // values may include reference strings like "$step1.id"
        boolean highRisk
) { }

// Plan.java
package com.citycopilot.agent.orchestrator;

import java.util.List;

public record Plan(List<PlanStep> steps, String finalAnswerTemplate) { }
```

### Task 13.2: PlanExecuteExecutor — TDD

**Files:**
- Create: `agent/src/test/java/com/citycopilot/agent/orchestrator/PlanExecuteExecutorTest.java`
- Create: `agent/src/main/java/com/citycopilot/agent/orchestrator/PlanExecuteExecutor.java`

- [ ] **Step 1: Write failing test**

```java
package com.citycopilot.agent.orchestrator;

import com.citycopilot.agent.gateway.ToolCallContext;
import com.citycopilot.agent.gateway.ToolGateway;
import com.citycopilot.agent.gateway.ToolResult;
import com.citycopilot.agent.llm.FakeLlm;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class PlanExecuteExecutorTest {

    @Test
    void resolves_refs_and_executes_two_step_plan() {
        // LLM returns first a plan, then a final-answer formatter.
        FakeLlm llm = new FakeLlm(List.of(
                """
                {"steps":[
                  {"idx":1,"tool":"a","args":{"q":"x"},"highRisk":false},
                  {"idx":2,"tool":"b","args":{"k":"$step1.id"},"highRisk":false}
                ],"finalAnswerTemplate":"id was $step1.id"}
                """,
                "id was 42"
        ));
        ToolGateway gateway = Mockito.mock(ToolGateway.class);
        Mockito.when(gateway.invoke(eq("a"), any(), any())).thenReturn(ToolResult.success("{\"id\":42}"));
        Mockito.when(gateway.invoke(eq("b"), any(), any())).thenReturn(ToolResult.success("ok"));

        PlanExecuteExecutor exec = new PlanExecuteExecutor(llm, gateway, 5);
        AgentResponse resp = exec.run(new AgentRequest("plan it",
                new ToolCallContext(1L, Set.of(), "c", "t")));

        assertThat(resp.finalAnswer()).contains("42");
        Mockito.verify(gateway).invoke(eq("b"), Mockito.contains("42"), any());
    }
}
```

- [ ] **Step 2: Implement PlanExecuteExecutor**

```java
package com.citycopilot.agent.orchestrator;

import com.citycopilot.agent.gateway.ToolResult;
import com.citycopilot.agent.gateway.ToolGateway;
import com.citycopilot.agent.llm.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class PlanExecuteExecutor implements Executor {

    private static final String PLAN_PROMPT = """
            You are a planner. Output a JSON plan:
            {"steps":[{"idx":N,"tool":"name","args":{...},"highRisk":bool}],"finalAnswerTemplate":"..."}
            Use $stepN.<jsonPath> to reference earlier outputs.
            """;
    private static final String FINAL_PROMPT = """
            Given the original question and all step outputs, write a final user-facing answer.
            """;

    private static final Pattern REF = Pattern.compile("\\$step(\\d+)(?:\\.([\\w.]+))?");

    private final LlmClient llm;
    private final ToolGateway gateway;
    private final int maxReplans;
    private final ObjectMapper mapper = new ObjectMapper();

    public PlanExecuteExecutor(LlmClient llm, ToolGateway gateway,
                               @Value("${citycopilot.agent.max-replans:2}") int maxReplans) {
        this.llm = llm;
        this.gateway = gateway;
        this.maxReplans = maxReplans;
    }

    @Override
    public AgentResponse run(AgentRequest req) {
        List<AgentStep> trace = new ArrayList<>();
        Map<Integer, JsonNode> stepOutputs = new HashMap<>();
        int replans = 0;

        String planRaw = llm.complete(PLAN_PROMPT, List.of(req.userMessage()));
        trace.add(new AgentStep.Thought(planRaw));

        Plan plan;
        try { plan = mapper.readValue(planRaw, Plan.class); }
        catch (Exception e) { return new AgentResponse("Plan parsing failed: " + e.getMessage(), trace, Mode.PLAN_EXECUTE, true); }

        for (PlanStep step : plan.steps()) {
            String argsJson;
            try { argsJson = mapper.writeValueAsString(resolveRefs(step.args(), stepOutputs)); }
            catch (Exception e) { return new AgentResponse("ref resolution failed", trace, Mode.PLAN_EXECUTE, true); }
            trace.add(new AgentStep.ToolCall(step.tool(), argsJson));
            ToolResult res = gateway.invoke(step.tool(), argsJson, req.ctx());
            String obs;
            try { obs = mapper.writeValueAsString(res); } catch (Exception ex) { obs = "{\"ok\":false}"; }
            trace.add(new AgentStep.Observation(step.tool(), obs, res.ok()));
            if (!res.ok() && res.error() != null && res.error().retryable() && replans < maxReplans) {
                replans++;
                String replanRaw = llm.complete(PLAN_PROMPT + " Previous attempt failed: " + res.error().message(),
                        List.of(req.userMessage()));
                trace.add(new AgentStep.Thought(replanRaw));
                try { plan = mapper.readValue(replanRaw, Plan.class); }
                catch (Exception e) { return new AgentResponse("Replan failed.", trace, Mode.PLAN_EXECUTE, true); }
                stepOutputs.clear();
                continue;
            }
            if (res.ok()) {
                try { stepOutputs.put(step.idx(), mapper.readTree(mapper.writeValueAsString(res.value()))); }
                catch (Exception ignored) { }
            }
        }

        String finalAnswer = llm.complete(FINAL_PROMPT,
                List.of(req.userMessage(), mapper.valueToTree(stepOutputs).toString()));
        trace.add(new AgentStep.FinalAnswer(finalAnswer));
        return new AgentResponse(finalAnswer, trace, Mode.PLAN_EXECUTE, false);
    }

    private Object resolveRefs(Object value, Map<Integer, JsonNode> outputs) {
        if (value instanceof String s) {
            Matcher m = REF.matcher(s);
            if (m.matches()) {
                int idx = Integer.parseInt(m.group(1));
                String path = m.group(2);
                JsonNode node = outputs.get(idx);
                if (node == null) return s;
                if (path == null) return node;
                for (String p : path.split("\\.")) node = node.path(p);
                if (node.isValueNode()) return node.asText();
                return node;
            }
            return s;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new HashMap<>();
            for (var e : map.entrySet()) out.put(e.getKey().toString(), resolveRefs(e.getValue(), outputs));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object o : list) out.add(resolveRefs(o, outputs));
            return out;
        }
        return value;
    }
}
```

- [ ] **Step 3: Run test, expect pass**

```bash
mvn -q -pl agent test -Dtest=PlanExecuteExecutorTest
```

- [ ] **Step 4: Add Router and OrchestratorRoot**

Create `agent/src/main/java/com/citycopilot/agent/orchestrator/Router.java`:

```java
package com.citycopilot.agent.orchestrator;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class Router {

    private static final List<String> MULTI_STEP_HINTS =
            List.of("然后", "接着", "最后", "并且", "再", "之后", "下单", "支付", "退款", "提醒");

    public Mode classify(String message) {
        long hits = MULTI_STEP_HINTS.stream().filter(message::contains).count();
        return hits >= 2 ? Mode.PLAN_EXECUTE : Mode.REACT;
    }
}
```

Create `agent/src/main/java/com/citycopilot/agent/orchestrator/AgentOrchestrator.java`:

```java
package com.citycopilot.agent.orchestrator;

import com.citycopilot.agent.trace.TraceRecord;
import com.citycopilot.agent.trace.TraceStore;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Primary
@Component
public class AgentOrchestrator implements Executor {

    private final Router router;
    private final ReactExecutor react;
    private final PlanExecuteExecutor planExecute;
    private final TraceStore store;

    public AgentOrchestrator(Router router, ReactExecutor react,
                             PlanExecuteExecutor planExecute, TraceStore store) {
        this.router = router;
        this.react = react;
        this.planExecute = planExecute;
        this.store = store;
    }

    @Override
    public AgentResponse run(AgentRequest req) {
        Mode mode = router.classify(req.userMessage());
        Executor delegate = mode == Mode.PLAN_EXECUTE ? planExecute : react;
        String traceId = req.ctx().traceId() == null ? UUID.randomUUID().toString() : req.ctx().traceId();
        AgentRequest scoped = new AgentRequest(req.userMessage(),
                new com.citycopilot.agent.gateway.ToolCallContext(
                        req.ctx().userId(), req.ctx().userPermissions(),
                        req.ctx().conversationId(), traceId));
        Instant start = Instant.now();
        AgentResponse resp = delegate.run(scoped);
        store.write(new TraceRecord(
                traceId, scoped.ctx().userId(), scoped.ctx().conversationId(),
                mode.name(), start, Duration.between(start, Instant.now()).toMillis(),
                resp.truncated(), resp.finalAnswer(), resp.steps()));
        return resp;
    }
}
```

Also: remove `@Primary` from `TracingExecutor` to avoid two `@Primary` `Executor` beans. Or delete `TracingExecutor` entirely now that `AgentOrchestrator` subsumes it.

```bash
# delete TracingExecutor since AgentOrchestrator replaces it
rm agent/src/main/java/com/citycopilot/agent/orchestrator/TracingExecutor.java
```

- [ ] **Step 5: Run tests**

```bash
mvn -q -pl agent test
```

Expected: all green.

- [ ] **Step 6: Commit**

```bash
git add agent/ -A
git commit -m "feat(agent): Plan-Execute-Replan executor + Router + AgentOrchestrator (REACT|PLAN_EXECUTE)"
```

---

## Phase 14: Expand tool catalog to 15-20

**Goal:** Annotate the remaining business operations to give Plan-Execute substrate to work on. Most additions are pure annotation.

### Task 14.1: Annotate write-side and admin operations

**Files (modify):**
- `core/src/main/java/com/citycopilot/core/service/impl/VoucherOrderServiceImpl.java`
- `core/src/main/java/com/citycopilot/core/service/impl/FollowServiceImpl.java`
- `core/src/main/java/com/citycopilot/core/service/impl/BlogServiceImpl.java`
- `core/src/main/java/com/citycopilot/core/service/impl/ShopServiceImpl.java`
- `core/src/main/java/com/citycopilot/core/service/AmapGeoService.java` (if present)
- `core/src/main/java/com/citycopilot/core/service/ShopGeoBatchService.java`
- `core/src/main/java/com/citycopilot/core/service/OperationLogService.java`

For each method, add `@AgentTool` with appropriate `name`, `description`, `requiredPermissions` (from `PermissionCodes`), and rate limit.

- [ ] **Step 1: Order-related**

In `VoucherOrderServiceImpl`:

```java
@AgentTool(name = "place_seckill_order", description = "Place a flash-sale (seckill) order for a given voucher id; returns the orderId.", idempotent = false, rateLimitPerMinute = 10)
public Result seckillVoucher(Long voucherId) { /* existing */ }

@AgentTool(name = "pay_order", description = "Mark a user's pending order as paid (simulated payment).", idempotent = true, rateLimitPerMinute = 30)
public Result payOrder(Long id) { /* existing */ }

@AgentTool(name = "cancel_order", description = "Cancel a user's pending unpaid order, restoring stock.", idempotent = true, rateLimitPerMinute = 30)
public Result cancelOrder(Long id) { /* existing */ }

@AgentTool(name = "refund_order", description = "Refund a paid order.", idempotent = true, rateLimitPerMinute = 30, requiredPermissions = {"order:write"})
public Result refundOrder(Long id) { /* existing */ }
```

- [ ] **Step 2: Social**

In `FollowServiceImpl`:

```java
@AgentTool(name = "follow_user", description = "Current user follows another user.")
public Result follow(Long followUserId, Boolean isFollow) { /* existing */ }
```

In `BlogServiceImpl`:

```java
@AgentTool(name = "get_blog_detail", description = "Get a blog (探店笔记) by id including author and like count.")
public Result queryBlogById(Long id) { /* existing */ }

@AgentTool(name = "get_follow_feed", description = "Get the timeline of blogs from users the current user follows, paginated by lastId.")
public Result queryBlogOfFollow(Long max, Integer offset) { /* existing */ }

@AgentTool(name = "publish_blog", description = "Publish a new 探店 blog post.", idempotent = false, rateLimitPerMinute = 5)
public Result saveBlog(Blog blog) { /* existing */ }
```

- [ ] **Step 3: Geo**

In `AmapGeoService` / `ShopGeoBatchService`:

```java
@AgentTool(name = "geocode_shop_address", description = "Call Amap geocoding for a shop, write back x/y/adcode and verified status.", requiredPermissions = {"geo:write"}, rateLimitPerMinute = 20)
public Result geocodeShop(Long shopId, String city, String address, Boolean verified) { /* existing */ }

@AgentTool(name = "start_batch_geocode", description = "Start an async batch geocode job over all shops missing coordinates.", requiredPermissions = {"geo:write"})
public Result startBatchGeocode(String city, Boolean onlyMissing, Integer limit) { /* existing */ }
```

- [ ] **Step 4: Audit**

In `OperationLogService`:

```java
@AgentTool(name = "query_audit_logs", description = "Query operation audit logs by target type / target id / time range.", requiredPermissions = {"audit:read"})
public Result query(String targetType, Long targetId, java.time.LocalDateTime from, java.time.LocalDateTime to) { /* existing */ }
```

- [ ] **Step 5: Verify aggregate test still passes; restart agent app, confirm log shows ≥15 tools**

```bash
mvn -q clean test
export DASHSCOPE_API_KEY=sk-...
mvn -pl agent spring-boot:run -pl agent
```

Expected log line: `ToolRegistry initialized with 15+ tools: [...]`.

- [ ] **Step 6: Commit**

```bash
git add core/
git commit -m "feat(core): expose 10+ additional business methods as @AgentTool"
```

---

## Phase 15: MCP Server

**Goal:** Expose the Tool Registry as MCP tools so Claude Desktop / Cursor can call them. Uses `spring-ai-starter-mcp-server`.

### Task 15.1: Bring up the starter

**Files:**
- Modify: `mcp-server/pom.xml`
- Create: `mcp-server/src/main/java/com/citycopilot/mcp/McpServerConfig.java`
- Create: `mcp-server/src/main/resources/application-mcp.yml`

- [ ] **Step 1: Add MCP server starter to `mcp-server/pom.xml`**

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
```

- [ ] **Step 2: Bridge ToolRegistry → MCP tool definitions**

Spring AI MCP server discovers tools via `org.springframework.ai.tool.method.MethodToolCallbackProvider` or beans of type `ToolCallback`. Our `ToolRegistryFunctionCallbackProvider` already produces those (per Phase 9). So we just wire a single provider bean.

Create `mcp-server/src/main/java/com/citycopilot/mcp/McpServerConfig.java`:

```java
package com.citycopilot.mcp;

import com.citycopilot.agent.gateway.ToolCallContext;
import com.citycopilot.agent.llm.ToolRegistryFunctionCallbackProvider;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

@Configuration
public class McpServerConfig {

    @Bean
    public ToolCallbackProvider mcpTools(ToolRegistryFunctionCallbackProvider provider) {
        return () -> {
            // System-level call context for MCP — no user identity (or use a "service" identity).
            ToolCallContext ctx = new ToolCallContext(null, Set.of("agent:tool:read"), "mcp", "mcp");
            return provider.callbacksFor(ctx).stream()
                    .map(fc -> (ToolCallback) fc)
                    .toArray(ToolCallback[]::new);
        };
    }
}
```

**Note**: the API surface may differ across Spring AI milestones; if `ToolCallback` and `ToolCallbackProvider` are not present at the version pinned, fall back to manually wiring `org.springframework.ai.mcp.server.McpServerFeatures.tool(name, description, schema, handler)` for each tool. Check `mvn dependency:tree -pl mcp-server | grep spring-ai-mcp` and read the corresponding starter's docs.

- [ ] **Step 3: Configure MCP transport**

Create `mcp-server/src/main/resources/application-mcp.yml`:

```yaml
spring:
  ai:
    mcp:
      server:
        name: citycopilot
        version: 0.1.0
        type: SYNC
        sse-message-endpoint: /mcp/messages
        sse-endpoint: /mcp/sse

server:
  port: 8090
```

- [ ] **Step 4: Smoke test via `curl` against MCP SSE endpoint**

```bash
mvn -pl mcp-server spring-boot:run -pl mcp-server
```

```bash
curl -N http://localhost:8090/mcp/sse
```

Expected: an SSE connection with an `endpoint` event referencing `/mcp/messages?sessionId=...`. Then in a separate terminal:

```bash
curl -X POST 'http://localhost:8090/mcp/messages?sessionId=<from above>' \
    -H 'Content-Type: application/json' \
    -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

Expected response: a JSON list containing your tool names.

- [ ] **Step 5: Add Claude Desktop config sample to `deploy/`**

Create `deploy/mcp/claude-desktop.json.sample`:

```json
{
  "mcpServers": {
    "citycopilot": {
      "command": "curl",
      "args": ["-N", "http://localhost:8090/mcp/sse"]
    }
  }
}
```

Doc this in README.

- [ ] **Step 6: Commit**

```bash
git add mcp-server/ deploy/mcp/
git commit -m "feat(mcp-server): bring up MCP server backed by shared ToolRegistry"
```

---

## Phase 16: Resilience4j integration

**Goal:** Per-tool circuit breaker, rate limiter, retry. LLM provider circuit breaker.

### Task 16.1: Wrap ToolGateway with Resilience4j decorators

**Files:**
- Modify: `agent/pom.xml`
- Modify: `agent/src/main/java/com/citycopilot/agent/gateway/ToolGateway.java`
- Create: `agent/src/main/java/com/citycopilot/agent/gateway/ResilienceRegistry.java`

- [ ] **Step 1: Add Resilience4j deps to `agent/pom.xml`**

```xml
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-circuitbreaker</artifactId>
</dependency>
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-ratelimiter</artifactId>
</dependency>
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-retry</artifactId>
</dependency>
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-timelimiter</artifactId>
</dependency>
```

- [ ] **Step 2: Per-tool resilience configuration**

Create `agent/src/main/java/com/citycopilot/agent/gateway/ResilienceRegistry.java`:

```java
package com.citycopilot.agent.gateway;

import com.citycopilot.agent.registry.ToolMeta;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class ResilienceRegistry {

    private final CircuitBreakerRegistry cbReg = CircuitBreakerRegistry.ofDefaults();
    private final RateLimiterRegistry rlReg = RateLimiterRegistry.ofDefaults();
    private final RetryRegistry rReg = RetryRegistry.ofDefaults();

    public CircuitBreaker breakerFor(ToolMeta meta) {
        return cbReg.circuitBreaker("tool:" + meta.name(), CircuitBreakerConfig.custom()
                .failureRateThreshold(50f)
                .slowCallRateThreshold(80f)
                .slowCallDurationThreshold(Duration.ofSeconds(3))
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .slidingWindowSize(20)
                .build());
    }

    public RateLimiter limiterFor(ToolMeta meta) {
        return rlReg.rateLimiter("tool:" + meta.name(), RateLimiterConfig.custom()
                .limitForPeriod(meta.rateLimitPerMinute())
                .limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(Duration.ofMillis(50))
                .build());
    }

    public Retry retryFor(ToolMeta meta) {
        return rReg.retry("tool:" + meta.name(), RetryConfig.custom()
                .maxAttempts(meta.idempotent() ? 3 : 1)
                .waitDuration(Duration.ofMillis(200))
                .build());
    }
}
```

- [ ] **Step 3: Wrap invocation in ToolGateway**

Modify `ToolGateway.invoke` invocation section:

```java
@Autowired
private ResilienceRegistry resilience;

// inside invoke, around the reflective call:
io.github.resilience4j.circuitbreaker.CircuitBreaker cb = resilience.breakerFor(meta);
io.github.resilience4j.ratelimiter.RateLimiter rl = resilience.limiterFor(meta);
io.github.resilience4j.retry.Retry retry = resilience.retryFor(meta);

java.util.concurrent.Callable<Object> call = () -> meta.method().invoke(meta.bean(), args);
java.util.concurrent.Callable<Object> decorated = io.github.resilience4j.decorators.Decorators.ofCallable(call)
        .withCircuitBreaker(cb)
        .withRateLimiter(rl)
        .withRetry(retry)
        .decorate();
Object result = decorated.call();
return ToolResult.success(result);
```

Wrap the call inside try/catch:
- `io.github.resilience4j.ratelimiter.RequestNotPermitted` → `ToolError(code="RATE_LIMIT", retryable=true)`
- `io.github.resilience4j.circuitbreaker.CallNotPermittedException` → `ToolError(code="CIRCUIT_OPEN", retryable=true)`
- `java.lang.reflect.InvocationTargetException` → existing business-error handling

- [ ] **Step 4: Smoke test by hammering one tool**

```bash
for i in $(seq 1 30); do curl -s -X POST -H 'Content-Type: application/json' \
    -d '{"message":"List shop categories","conversationId":"smoke"}' \
    http://localhost:8081/agent/chat > /dev/null & done; wait
```

Expected: subsequent calls return `RATE_LIMIT` once per-minute quota exceeded. Log lines confirm.

- [ ] **Step 5: Commit**

```bash
git add agent/
git commit -m "feat(agent): Resilience4j circuit breaker + rate limiter + retry around tool calls"
```

### Task 16.2: LLM provider circuit breaker

**Files:**
- Modify: `agent/src/main/java/com/citycopilot/agent/llm/SpringAiLlmClient.java`

- [ ] **Step 1: Wrap `chat.prompt().call()` with a separate CircuitBreaker**

```java
@Component
public class SpringAiLlmClient implements LlmClient {
    private final ChatClient chat;
    private final CircuitBreaker llmCb;

    public SpringAiLlmClient(ChatClient chat) {
        this.chat = chat;
        this.llmCb = io.github.resilience4j.circuitbreaker.CircuitBreaker.of("llm", io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .waitDurationInOpenState(java.time.Duration.ofSeconds(30))
                .slidingWindowSize(10)
                .build());
    }

    @Override
    public String complete(String systemPrompt, List<String> historyJson) {
        return llmCb.executeSupplier(() -> {
            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(systemPrompt));
            for (String entry : historyJson) messages.add(new UserMessage(entry));
            return chat.prompt(new Prompt(messages)).call().content();
        });
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add agent/
git commit -m "feat(agent): circuit breaker around LLM provider calls"
```

---

## Phase 17: Ops Agent (Redis Stream consumer)

**Goal:** A background worker that consumes a Redis Stream and runs Plan-Execute jobs without UI feedback.

### Task 17.1: Stream consumer

**Files:**
- Create: `agent/src/main/java/com/citycopilot/agent/multi/OpsStreamConsumer.java`
- Create: `agent/src/main/java/com/citycopilot/agent/multi/OpsAgent.java`

- [ ] **Step 1: OpsAgent — thin wrapper around PlanExecute**

```java
package com.citycopilot.agent.multi;

import com.citycopilot.agent.gateway.ToolCallContext;
import com.citycopilot.agent.orchestrator.AgentRequest;
import com.citycopilot.agent.orchestrator.AgentResponse;
import com.citycopilot.agent.orchestrator.PlanExecuteExecutor;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

@Component
public class OpsAgent {

    private final PlanExecuteExecutor exec;

    public OpsAgent(PlanExecuteExecutor exec) { this.exec = exec; }

    public AgentResponse run(String taskPrompt) {
        return exec.run(new AgentRequest(taskPrompt,
                new ToolCallContext(0L, Set.of("ops:agent", "geo:write", "audit:read"),
                        "ops-" + UUID.randomUUID(), UUID.randomUUID().toString())));
    }
}
```

- [ ] **Step 2: Stream consumer**

```java
package com.citycopilot.agent.multi;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

@Slf4j
@Component
public class OpsStreamConsumer {

    private static final String STREAM_KEY = "agent.ops.tasks";
    private static final String GROUP = "ops-workers";
    private static final String CONSUMER = "ops-1";

    private final StringRedisTemplate redis;
    private final OpsAgent opsAgent;

    public OpsStreamConsumer(StringRedisTemplate redis, OpsAgent opsAgent) {
        this.redis = redis;
        this.opsAgent = opsAgent;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        try { redis.opsForStream().createGroup(STREAM_KEY, ReadOffset.from("0"), GROUP); }
        catch (Exception e) { log.debug("group exists or stream missing: {}", e.getMessage()); }

        Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "ops-consumer"); t.setDaemon(true); return t; })
                .submit(this::loop);
    }

    void loop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                        Consumer.from(GROUP, CONSUMER),
                        StreamReadOptions.empty().count(1).block(Duration.ofSeconds(5)),
                        StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()));
                if (records == null) continue;
                for (var rec : records) {
                    handle(rec);
                    redis.opsForStream().acknowledge(STREAM_KEY, GROUP, rec.getId());
                }
            } catch (Exception e) {
                log.error("ops consumer error", e);
                try { Thread.sleep(1000); } catch (InterruptedException ie) { return; }
            }
        }
    }

    void handle(MapRecord<String, Object, Object> rec) {
        Map<Object, Object> v = rec.getValue();
        String prompt = String.valueOf(v.get("prompt"));
        log.info("ops agent handling task: {}", prompt);
        var resp = opsAgent.run(prompt);
        log.info("ops agent done, truncated={}", resp.truncated());
    }
}
```

- [ ] **Step 3: Smoke test**

```bash
redis-cli XADD agent.ops.tasks '*' prompt "Run a batch geocode for shops in 杭州 that are missing coordinates, then summarize the result."
```

Watch app logs for `ops agent handling task: ...` followed by tool calls and completion.

- [ ] **Step 4: Commit**

```bash
git add agent/
git commit -m "feat(agent): Ops Agent + Redis Stream consumer for async tasks"
```

---

## Phase 18: Critic Agent (optional)

**Goal:** Insert a single LLM check before high-risk plan steps (place_seckill_order, pay_order, refund_order, publish_blog, start_batch_geocode).

### Task 18.1: CriticAgent + plan-time hook

**Files:**
- Create: `agent/src/main/java/com/citycopilot/agent/multi/CriticAgent.java`
- Modify: `agent/src/main/java/com/citycopilot/agent/orchestrator/PlanExecuteExecutor.java`

- [ ] **Step 1: CriticAgent**

```java
package com.citycopilot.agent.multi;

import com.citycopilot.agent.llm.LlmClient;
import com.citycopilot.agent.orchestrator.PlanStep;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CriticAgent {

    private final LlmClient llm;
    private final ObjectMapper mapper = new ObjectMapper();

    public CriticAgent(LlmClient llm) { this.llm = llm; }

    public boolean approve(String userIntent, List<PlanStep> priorSteps, PlanStep step) {
        try {
            String history = mapper.writeValueAsString(priorSteps);
            String review = llm.complete(
                    "You are a safety reviewer. Reply 'APPROVE' or 'REJECT: <reason>'.",
                    List.of("intent: " + userIntent, "prior: " + history,
                            "next: " + mapper.writeValueAsString(step)));
            return review.trim().toUpperCase().startsWith("APPROVE");
        } catch (Exception e) {
            return false;
        }
    }
}
```

- [ ] **Step 2: Hook into PlanExecuteExecutor**

In `PlanExecuteExecutor`, before invoking a step with `highRisk=true`:

```java
@Autowired private CriticAgent critic;
// or inject via constructor

if (step.highRisk()) {
    if (!critic.approve(req.userMessage(), prior, step)) {
        trace.add(new AgentStep.Observation(step.tool(), "{\"ok\":false,\"reason\":\"critic_rejected\"}", false));
        return new AgentResponse("Critic rejected this plan.", trace, Mode.PLAN_EXECUTE, true);
    }
}
```

(Implementation note: keep a `List<PlanStep> prior = new ArrayList<>()` updated as the loop progresses.)

- [ ] **Step 3: Commit**

```bash
git add agent/
git commit -m "feat(agent): CriticAgent gate before high-risk plan steps"
```

---

## Phase 19: Eval Harness

**Goal:** YAML-driven case runner; outputs a Markdown report with tool-call accuracy, answer keyword hit, latency, token estimates.

### Task 19.1: Eval case model + runner

**Files:**
- Create: `eval/src/main/java/com/citycopilot/eval/EvalCase.java`
- Create: `eval/src/main/java/com/citycopilot/eval/EvalReport.java`
- Create: `eval/src/main/java/com/citycopilot/eval/EvalRunner.java`
- Create: `eval/src/main/java/com/citycopilot/eval/MarkdownReportWriter.java`
- Create: `eval/src/test/resources/cases/nearby_hotpot.yaml`
- Create: `eval/src/test/java/com/citycopilot/eval/EvalRunnerTest.java`

- [ ] **Step 1: YAML schema**

```yaml
# eval/src/test/resources/cases/nearby_hotpot.yaml
name: nearby_hotpot_with_voucher_order
input: "附近 50 块以内有券的火锅，帮我下单一个"
context:
  userId: 1001
  location: { x: 120.149, y: 30.282 }
expectMode: PLAN_EXECUTE
expectTools:
  - tool: search_nearby_shops
  - tool: list_vouchers_by_shop
  - tool: place_seckill_order
expectKeywords: ["下单成功"]
expectNoKeywords: ["抱歉", "无法"]
```

- [ ] **Step 2: Data classes**

```java
// EvalCase.java
package com.citycopilot.eval;

import java.util.List;
import java.util.Map;

public record EvalCase(
        String name,
        String input,
        Map<String, Object> context,
        String expectMode,
        List<Map<String, Object>> expectTools,
        List<String> expectKeywords,
        List<String> expectNoKeywords
) { }
```

- [ ] **Step 3: Runner**

```java
package com.citycopilot.eval;

import com.citycopilot.agent.gateway.ToolCallContext;
import com.citycopilot.agent.orchestrator.AgentRequest;
import com.citycopilot.agent.orchestrator.AgentResponse;
import com.citycopilot.agent.orchestrator.AgentStep;
import com.citycopilot.agent.orchestrator.Executor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Component
public class EvalRunner {

    @Autowired
    private Executor executor;

    public EvalReport.CaseResult runOne(EvalCase c) {
        Instant start = Instant.now();
        AgentResponse resp = executor.run(new AgentRequest(c.input(),
                new ToolCallContext(((Number) c.context().getOrDefault("userId", 0)).longValue(),
                        Set.of(), "eval-" + c.name(), null)));
        long durMs = Duration.between(start, Instant.now()).toMillis();

        boolean modeOk = c.expectMode() == null || c.expectMode().equals(resp.mode().name());
        Set<String> calledTools = new HashSet<>();
        for (AgentStep s : resp.steps()) if (s instanceof AgentStep.ToolCall tc) calledTools.add(tc.tool());
        boolean toolsOk = c.expectTools() == null || c.expectTools().stream()
                .allMatch(t -> calledTools.contains((String) t.get("tool")));
        boolean kwOk = c.expectKeywords() == null || c.expectKeywords().stream()
                .allMatch(k -> resp.finalAnswer() != null && resp.finalAnswer().contains(k));
        boolean kwNoOk = c.expectNoKeywords() == null || c.expectNoKeywords().stream()
                .noneMatch(k -> resp.finalAnswer() != null && resp.finalAnswer().contains(k));

        return new EvalReport.CaseResult(c.name(), modeOk && toolsOk && kwOk && kwNoOk,
                durMs, calledTools, resp.finalAnswer(), modeOk, toolsOk, kwOk && kwNoOk);
    }

    public EvalReport runAll(Path casesDir) throws Exception {
        Yaml yaml = new Yaml();
        List<EvalReport.CaseResult> all = new ArrayList<>();
        try (var stream = Files.list(casesDir)) {
            for (var p : stream.toList()) {
                try (InputStream in = Files.newInputStream(p)) {
                    Map<String, Object> raw = yaml.load(in);
                    EvalCase c = new EvalCase(
                            (String) raw.get("name"),
                            (String) raw.get("input"),
                            (Map<String, Object>) raw.getOrDefault("context", Map.of()),
                            (String) raw.get("expectMode"),
                            (List<Map<String, Object>>) raw.getOrDefault("expectTools", List.of()),
                            (List<String>) raw.getOrDefault("expectKeywords", List.of()),
                            (List<String>) raw.getOrDefault("expectNoKeywords", List.of())
                    );
                    all.add(runOne(c));
                }
            }
        }
        return new EvalReport(all);
    }
}
```

- [ ] **Step 4: Report writer**

```java
package com.citycopilot.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.List;

public record EvalReport(List<CaseResult> results) {

    public record CaseResult(
            String name, boolean pass, long durationMs, java.util.Set<String> calledTools,
            String finalAnswer, boolean modeOk, boolean toolsOk, boolean keywordsOk
    ) { }

    public String summary() {
        long total = results.size();
        long passed = results.stream().filter(CaseResult::pass).count();
        double avgMs = results.stream().mapToLong(CaseResult::durationMs).average().orElse(0);
        return String.format("Total: %d | Passed: %d (%.0f%%) | Avg latency: %.0f ms",
                total, passed, 100.0 * passed / total, avgMs);
    }

    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Eval report — ").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\n\n");
        sb.append("**").append(summary()).append("**\n\n");
        sb.append("| Case | Pass | Mode | Tools | Keywords | Latency | Tools called |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        for (var r : results) {
            sb.append("| ").append(r.name()).append(" | ")
              .append(r.pass() ? "✓" : "✗").append(" | ")
              .append(r.modeOk() ? "✓" : "✗").append(" | ")
              .append(r.toolsOk() ? "✓" : "✗").append(" | ")
              .append(r.keywordsOk() ? "✓" : "✗").append(" | ")
              .append(r.durationMs()).append(" ms | ")
              .append(String.join(", ", r.calledTools())).append(" |\n");
        }
        return sb.toString();
    }

    public void writeTo(Path dir) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve("eval-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".md");
        Files.writeString(file, toMarkdown());
    }
}
```

- [ ] **Step 5: CLI entry**

In `eval/src/main/java/com/citycopilot/eval/EvalCli.java`:

```java
package com.citycopilot.eval;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@SpringBootApplication(scanBasePackages = {
        "com.citycopilot.core", "com.citycopilot.agent", "com.citycopilot.eval"
})
public class EvalCli {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(EvalCli.class);
        app.setAdditionalProfiles("eval");
        app.run(args);
    }

    @Component
    @Profile("eval")
    static class Runner implements CommandLineRunner {
        private final EvalRunner runner;
        Runner(EvalRunner runner) { this.runner = runner; }
        @Override public void run(String... args) throws Exception {
            Path casesDir = Path.of(args.length > 0 ? args[0] : "eval/src/test/resources/cases");
            EvalReport report = runner.runAll(casesDir);
            System.out.println(report.summary());
            report.writeTo(Path.of("eval/reports"));
        }
    }
}
```

- [ ] **Step 6: Add 30 cases gradually**

Create at least 5 initial yaml files in `eval/src/test/resources/cases/`:
- `nearby_hotpot.yaml` (already above)
- `list_categories.yaml`
- `shop_detail.yaml`
- `my_orders.yaml`
- `cancel_order.yaml`

(Plan adds 25 more over the week; each is 10-line yaml.)

- [ ] **Step 7: Run eval**

```bash
export DASHSCOPE_API_KEY=sk-...
mvn -pl eval spring-boot:run -pl eval -Dspring-boot.run.arguments="eval/src/test/resources/cases"
```

Expected: a summary line printed + `eval/reports/eval-YYYYMMDD-HHMMSS.md` written.

- [ ] **Step 8: Commit**

```bash
git add eval/
git commit -m "feat(eval): YAML-driven eval runner with markdown report"
```

---

## Phase 20: Observability (Micrometer + Prometheus)

**Goal:** Expose Prometheus metrics for tool calls, LLM calls, agent loop counts; ship a Grafana dashboard JSON.

### Task 20.1: Wire Micrometer + Actuator

**Files:**
- Modify: `core/pom.xml` and `agent/pom.xml` (add actuator + prometheus registry)
- Modify: `core/src/main/resources/application.yml`
- Modify: `agent/src/main/java/com/citycopilot/agent/gateway/ToolGateway.java`
- Modify: `agent/src/main/java/com/citycopilot/agent/llm/SpringAiLlmClient.java`
- Create: `deploy/prometheus/prometheus.yml`
- Create: `deploy/grafana/citycopilot-dashboard.json`

- [ ] **Step 1: Add deps**

In both `core/pom.xml` and `agent/pom.xml`:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

In `application.yml`:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  metrics:
    tags:
      application: city-copilot
```

- [ ] **Step 2: Tag metrics on tool calls**

In `ToolGateway`:

```java
@Autowired private io.micrometer.core.instrument.MeterRegistry meterRegistry;

// inside invoke, around the call:
io.micrometer.core.instrument.Timer.Sample sample = io.micrometer.core.instrument.Timer.start(meterRegistry);
ToolResult outcome;
try {
    outcome = /* invoke */;
} finally {
    sample.stop(io.micrometer.core.instrument.Timer.builder("citycopilot.tool")
            .tag("tool", name).tag("ok", String.valueOf(outcome != null && outcome.ok()))
            .register(meterRegistry));
}
return outcome;
```

(Carefully arrange variable scope — use a `final` array hack if needed.)

- [ ] **Step 3: Tag metrics on LLM calls**

In `SpringAiLlmClient`:

```java
// timer around llmCb.executeSupplier
```

- [ ] **Step 4: Prometheus config**

```yaml
# deploy/prometheus/prometheus.yml
global:
  scrape_interval: 15s
scrape_configs:
  - job_name: citycopilot
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['host.docker.internal:8081']
```

- [ ] **Step 5: Grafana dashboard JSON**

```json
{
  "title": "CityCopilot Overview",
  "panels": [
    { "type": "graph", "title": "Tool calls / sec", "targets": [{ "expr": "rate(citycopilot_tool_count[1m])" }] },
    { "type": "graph", "title": "Tool latency p95", "targets": [{ "expr": "histogram_quantile(0.95, sum(rate(citycopilot_tool_seconds_bucket[1m])) by (le, tool))" }] },
    { "type": "graph", "title": "LLM call latency", "targets": [{ "expr": "histogram_quantile(0.95, sum(rate(citycopilot_llm_seconds_bucket[1m])) by (le))" }] }
  ]
}
```

(Minimal placeholder; expand to a real dashboard in `deploy/grafana/citycopilot-dashboard.json` with proper Grafana schema. Use Grafana UI to design, then export JSON.)

- [ ] **Step 6: Commit**

```bash
git add core/ agent/ deploy/
git commit -m "feat(observability): Micrometer/Prometheus metrics + dashboard JSON + scrape config"
```

---

## Phase 21: Traces Dashboard Frontend

**Goal:** Frontend page that fetches a trace by id and renders a step-by-step timeline.

### Task 21.1: Backend traces API

**Files:**
- Modify: `agent/src/main/java/com/citycopilot/agent/sse/AgentController.java` (add `GET /agent/traces/{id}` and `GET /agent/traces` list)

- [ ] **Step 1: Add endpoints**

```java
@org.springframework.beans.factory.annotation.Autowired
private com.citycopilot.agent.trace.TraceStore traceStore;

@GetMapping("/traces/{id}")
public org.springframework.http.ResponseEntity<com.citycopilot.agent.trace.TraceRecord> trace(@PathVariable String id) {
    return traceStore.read(id)
            .map(org.springframework.http.ResponseEntity::ok)
            .orElseGet(() -> org.springframework.http.ResponseEntity.notFound().build());
}
```

For list, scan Redis with `SCAN trace:*` and return summary metadata. Add to TraceStore:

```java
public java.util.List<String> recentTraceIds(int limit) {
    java.util.Set<String> keys = redis.keys(KEY_PREFIX + "*");
    return keys == null ? java.util.List.of()
            : keys.stream().limit(limit).map(k -> k.substring(KEY_PREFIX.length())).toList();
}
```

(Note: `KEYS` is O(N) — for a real app use `SCAN`. For a personal portfolio with <10k traces this is fine.)

### Task 21.2: Frontend page

**Files:**
- Create: `frontend/src/views/TracesView.vue`
- Modify: `frontend/src/router/index.ts`

- [ ] **Step 1: Page**

```vue
<template>
  <div class="traces">
    <h2>Agent traces</h2>
    <ul>
      <li v-for="t in list" :key="t" @click="open(t)">{{ t }}</li>
    </ul>
    <section v-if="selected">
      <h3>{{ selected.traceId }}</h3>
      <p>Mode: <strong>{{ selected.mode }}</strong>, Duration: {{ selected.durationMs }} ms, Truncated: {{ selected.truncated }}</p>
      <div v-for="(s, i) in selected.steps" :key="i" class="step">
        <span class="badge">{{ stepType(s) }}</span>
        <pre>{{ formatStep(s) }}</pre>
      </div>
      <h4>Final answer</h4>
      <pre>{{ selected.finalAnswer }}</pre>
    </section>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';

const list = ref<string[]>([]);
const selected = ref<any>(null);

onMounted(async () => {
  const r = await fetch('/api/agent/traces').then(r => r.json());
  list.value = r;
});

async function open(id: string) {
  selected.value = await fetch('/api/agent/traces/' + id).then(r => r.json());
}

function stepType(s: any) {
  return Object.keys(s)[0];
}
function formatStep(s: any) {
  return JSON.stringify(s, null, 2);
}
</script>

<style scoped>
.traces { max-width: 960px; margin: 0 auto; padding: 1rem; }
.step { margin: .5rem 0; border-left: 3px solid #888; padding-left: .5rem; }
.badge { font-size: .8em; background: #eee; padding: .1rem .3rem; border-radius: .25rem; margin-right: .5rem; }
pre { white-space: pre-wrap; font-size: .85em; }
</style>
```

- [ ] **Step 2: Route**

```ts
{ path: '/admin/traces', component: () => import('../views/TracesView.vue') }
```

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/java/com/citycopilot/agent/sse/AgentController.java agent/src/main/java/com/citycopilot/agent/trace/TraceStore.java frontend/src/views/TracesView.vue frontend/src/router/
git commit -m "feat(frontend): traces dashboard listing recent agent runs"
```

---

## Phase 22: Documentation, demo video, resume description

**Goal:** All collateral needed for a great resume submission.

### Task 22.1: README upgrade with metrics

**Files:**
- Modify: `D:\programming\hmdp\README.md`
- Create: `docs/RESUME.md`

- [ ] **Step 1: Run eval to get real numbers**

```bash
export DASHSCOPE_API_KEY=sk-...
mvn -pl eval spring-boot:run -pl eval
cat eval/reports/eval-*.md | tail -1
```

Record the latest summary line into README.

- [ ] **Step 2: Run a quick seckill load test (manual via `wrk` or `ab`)**

```bash
# precondition: app running, voucher id 1 exists with stock
ab -n 1000 -c 50 -H 'Authorization: <token>' http://localhost:8081/voucher-order/seckill/1
```

Record total req/sec and 95th percentile. Then disable rate limiter (or set a high limit) and repeat for "before/after" framing.

- [ ] **Step 3: Update README with `## Metrics` section**

Append to README:

```markdown
## Metrics (v0.2)

| Metric | Value | Notes |
|---|---|---|
| Tool-call accuracy | _X_% | over 30 eval cases |
| Average response latency | _Y_ ms | end-to-end agent run |
| Average token usage | _Z_ tokens | per conversation turn |
| Seckill throughput | _A_ → _B_ req/sec | before/after Resilience4j tuning |
| Tools registered | 18 | counted at startup |
```

Replace `_X_/_Y_/_Z_/_A_/_B_` with real numbers.

- [ ] **Step 4: Write `docs/RESUME.md` — copy-paste-ready resume bullet**

```markdown
# Resume bullets — CityCopilot

**Project: CityCopilot — Acting AI Agent for Local Life Services**
Tech: Java 17, Spring Boot 3.2, Spring AI Alibaba, MyBatis-Plus, MySQL, Redis, Resilience4j, Micrometer + Prometheus, Vue 3 + TypeScript

- Designed and built a modular-monolith AI Agent platform (4 Maven modules: core/agent/mcp-server/eval). Self-built Tool Registry via `@AgentTool` annotation exposes 18 business operations simultaneously as LLM Function Calling tools and MCP server tools — one annotation, two surfaces.
- Implemented Orchestrator with two modes: ReAct (single-loop) for simple queries and Plan-Execute-Replan (multi-step planner with reference resolution) for complex tasks; Router auto-selects by intent.
- Multi-Agent collaboration: Dialog Agent (synchronous SSE), Ops Agent (Redis Stream–driven async tasks), Critic Agent (LLM-based guardrail for high-risk operations).
- Implemented MCP server (Spring AI MCP starter), exposing business capabilities to Claude / Cursor / any MCP client over SSE.
- Eval Harness: 30 YAML cases, X% tool-call accuracy, Y ms average latency, Z tokens average.
- Production-grade backend reused from prior work: Redis Lua–driven seckill (QPS A → B with Resilience4j), Redisson distributed lock, Redis GEO nearby search, role-based permission, audit log.
- Observability: Micrometer + Prometheus + Grafana dashboard covering tool / LLM / business dimensions.
```

- [ ] **Step 5: Record a demo (manual, no code)**

Record a 2-3 minute screen capture:
1. `/agent` chat: "List shop categories" → demonstrates tool call card
2. `/agent` chat: a multi-step "find nearby hotpot under 50 with voucher, order it" → demonstrates Plan-Execute and tool cards
3. `/admin/traces`: click into the trace → show the timeline
4. (optional) Claude Desktop calling `list_shop_types` via MCP

Save mp4 to `docs/demo.mp4`, embed link in README.

- [ ] **Step 6: Commit**

```bash
git add README.md docs/RESUME.md docs/demo.mp4
git commit -m "docs: README metrics, RESUME.md bullets, demo video"
git tag v0.2
```

---

## Phase 23: Cleanup and graduate as portfolio

**Goal:** Remove deprecated paths, polish.

- [ ] **Step 1: Remove the deprecated `AiAssistantService` (873 lines)**

Confirm by grep that nothing imports it anymore (controllers should all be using the new `AgentOrchestrator`).

```bash
grep -rn "AiAssistantService" core/src/ agent/src/ 2>/dev/null
```

If clean, delete:

```bash
git rm core/src/main/java/com/citycopilot/core/service/AiAssistantService.java
git rm core/src/main/java/com/citycopilot/core/dto/AiChatRequest.java
git rm core/src/main/java/com/citycopilot/core/dto/AiChatResponse.java
git rm core/src/main/java/com/citycopilot/core/controller/AiController.java
```

- [ ] **Step 2: Final aggregate test**

```bash
mvn -q clean verify
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit and tag**

```bash
git commit -m "chore: remove deprecated AiAssistantService superseded by AgentOrchestrator"
git tag v0.2.1
git log --oneline | head -30
```

---

## Self-Review Checklist

Before considering this plan executed, the reviewer should verify:

- [ ] **Spec coverage** — every section of `docs/superpowers/specs/2026-05-24-citycopilot-design.md` is implemented:
  - §1 Identity → Phase 3 (rename) + Phase 22 (README)
  - §2 Architecture → Phase 2 (Maven modules), Phase 4 (Spring AI), Phase 15 (MCP)
  - §3.1 Tool Registry → Phases 5, 6, 7, 8, 14
  - §3.2 Orchestrator (ReAct + Plan-Execute) → Phases 10, 13
  - §3.3 Multi-Agent (Dialog/Ops/Critic) → Phases 12, 17, 18
  - §3.4 MCP Server → Phase 15
  - §3.5 Eval Harness → Phase 19
  - §4 Error handling → Phases 7 (validation), 16 (resilience)
  - §5 Security → Phases 7 (permission check), 18 (critic), audit reuses core
  - §6 Timeline → matches Phase Map at top of this doc
  - §7 Resume deliverables → Phase 22
  - §8 Risks → Phase 0 (compatibility POC), Phase 16 (resilience)

- [ ] **Type consistency** — `ToolMeta`, `ToolCallContext`, `ToolResult`, `ToolError`, `AgentStep`, `AgentRequest`, `AgentResponse`, `Mode`, `Plan`, `PlanStep` are all used with consistent fields across all phases.

- [ ] **No placeholders** — no "TBD" / "TODO" / "implement later" / "similar to N" / "add error handling" without specifics remain in this plan.

- [ ] **Test coverage** — TDD applied for: ToolRegistry, JsonSchemaGenerator, ToolGateway, ReactExecutor, PlanExecuteExecutor, TraceStore. Smoke tests for: Spring AI smoke (Phase 4), SSE (Phase 12), MCP server (Phase 15), Eval (Phase 19), Observability (Phase 20).

---

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-05-24-citycopilot-implementation.md`.**

Two execution options:

**1. Subagent-Driven (recommended)** — A fresh subagent is dispatched per task / per phase; you (or the user) reviews between phases. Best for keeping context narrow on a large plan like this.

**2. Inline Execution** — Execute phases one at a time in this session, with checkpoints. Higher context cost but less coordination overhead.

The plan is designed so that **Phase 12 produces a v0.1 MVP** that is independently shippable to a resume. If interview deadlines push, stop at Phase 12, ship the resume, then resume Phases 13+ during interview cycles.

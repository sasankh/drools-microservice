# Java 25 Setup Guide

**Version**: 2.0.0
**Last Updated**: 2026-05-09
**Requirement**: Java 25 (LTS) - Enforced by Maven

---

## Table of Contents

1. [Overview](#overview)
2. [Why Java 25?](#why-java-25)
3. [Installation](#installation)
4. [Environment Setup](#environment-setup)
5. [Verification](#verification)
6. [Troubleshooting](#troubleshooting)
7. [IDE Configuration](#ide-configuration)
8. [Docker Development](#docker-development)

---

## Overview

### Java 25 Requirement

This project **requires Java 25** specifically. The Maven Enforcer Plugin will automatically fail builds if using a different Java version.

**Critical**: As of 2026-05-09, the project enforces Java 25 at build time to prevent compatibility issues.

### What's Enforced

```xml
<!-- pom.xml -->
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-enforcer-plugin</artifactId>
    <version>3.6.2</version>
    <executions>
        <execution>
            <id>enforce-java</id>
            <goals><goal>enforce</goal></goals>
            <configuration>
                <rules>
                    <requireJavaVersion>
                        <version>[25,26)</version>
                        <message>❌ Java 25 is required!</message>
                    </requireJavaVersion>
                </rules>
            </configuration>
        </execution>
    </executions>
</plugin>
```

**Result**: Build fails with clear error message if Java 25 is not used.

---

## Why Java 25?

### Technical Reasons

1. **Spring Boot 3.5.3 Baseline**: Forward-compatible with Java 25 (minimum is Java 17)
2. **Drools 10.2.0 Compatibility**: Drools 10 baselines on JDK 17+; Java 25 is forward-compatible
3. **LTS Support**: Long-term support until September 2026+
4. **Performance**: G1GC improvements, better memory management
5. **Security**: Latest security patches and updates

### What Breaks with Other Versions

**Java 11** (too old):
- Spring Boot 3.x not supported
- Missing language features
- Security vulnerabilities

**Java 21/23** (too new):
- Potential compatibility issues
- Untested with Drools 10.2.0
- Different GC behavior
- May introduce bugs

**Result**: Stick with Java 25 for guaranteed compatibility.

---

## Installation

### macOS (Homebrew)

**Install Java 25**:
```bash
# Install OpenJDK 25
brew install openjdk@25

# Link it (optional)
sudo ln -sfn /opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk \
    /Library/Java/JavaVirtualMachines/openjdk-25.jdk

# Verify installation
/opt/homebrew/opt/openjdk@25/bin/java --version
```

**Expected Output**:
```
openjdk 25.0.x 2024-xx-xx
OpenJDK Runtime Environment Homebrew (build 25.0.x+x)
OpenJDK 64-Bit Server VM Homebrew (build 25.0.x+x, mixed mode, sharing)
```

---

### Linux (Ubuntu/Debian)

```bash
# Update package list
sudo apt update

# Install OpenJDK 25
sudo apt install openjdk-25-jdk

# Verify
java -version
```

---

### Linux (RHEL/CentOS/Fedora)

```bash
# Install OpenJDK 25
sudo dnf install java-25-openjdk-devel

# Or on older systems
sudo yum install java-25-openjdk-devel

# Verify
java -version
```

---

### Windows

**Option 1: Adoptium (Recommended)**
1. Download from https://adoptium.net/
2. Select:
   - Version: 25 (LTS)
   - JVM: HotSpot
   - Operating System: Windows
3. Run installer
4. Verify:
   ```cmd
   java -version
   ```

**Option 2: Oracle JDK**
1. Download from https://www.oracle.com/java/technologies/downloads/ (select JDK 25)
2. Run installer
3. Set JAVA_HOME in System Properties

---

## Environment Setup

### Quick Setup (Temporary)

Use the provided setup script for a temporary environment (current terminal only):

```bash
# Navigate to project directory
cd /path/to/drools-microservice

# Run setup script
source ./set-java-env.sh
```

**Output**:
```
✅ Java environment configured:
   JAVA_HOME: /opt/homebrew/Cellar/openjdk@25/25.0.2/libexec/openjdk.jdk/Contents/Home
   Java version:
openjdk version "25.0.2" 2024-10-15
OpenJDK Runtime Environment Homebrew (build 25.0.2+0)
OpenJDK 64-Bit Server VM Homebrew (build 25.0.2+0, mixed mode, sharing)

   Maven will use:
Java version: 25.0.2, vendor: Homebrew
```

**Limitations**:
- Only active in current terminal session
- Must run again when opening new terminal
- Doesn't affect system-wide Java

---

### Permanent Setup (Recommended)

#### macOS/Linux (Bash/Zsh)

**Add to `~/.zshrc` or `~/.bashrc`**:

```bash
# Java 25 for Drools Rule Engine
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
export PATH="$JAVA_HOME/bin:$PATH"
```

**Apply Changes**:
```bash
# Reload shell configuration
source ~/.zshrc  # or source ~/.bashrc

# Verify
java -version
mvn -version | grep "Java version"
```

---

#### Windows

**Set Environment Variables**:

1. Open System Properties (Win + Pause)
2. Click "Advanced system settings"
3. Click "Environment Variables"
4. Under "System variables", click "New":
   - Variable name: `JAVA_HOME`
   - Variable value: `C:\Program Files\Java\jdk-25`
5. Find "Path" variable, click "Edit"
6. Add new entry: `%JAVA_HOME%\bin`
7. Click "OK" on all dialogs
8. **Restart terminal**

**Verify**:
```cmd
java -version
mvn -version
```

---

### Multiple Java Versions

If you have multiple Java versions installed:

#### macOS

**List all installed Java versions**:
```bash
/usr/libexec/java_home -V
```

**Output**:
```
Matching Java Virtual Machines (3):
    23.0.1 (arm64) "Homebrew" - "OpenJDK 23.0.1" /opt/homebrew/Cellar/openjdk/23.0.1/...
    25.0.2 (arm64) "Homebrew" - "OpenJDK 25.0.2" /opt/homebrew/Cellar/openjdk@25/25.0.2/...
    11.0.15 (arm64) "Homebrew" - "OpenJDK 11.0.15" /opt/homebrew/Cellar/openjdk@11/11.0.15/...
```

**Switch to Java 25**:
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
```

---

#### Linux

**Use update-alternatives**:
```bash
# List available Java versions
sudo update-alternatives --config java

# Select Java 25 from the list
# Example: Enter '2' to select java-25-openjdk

# Verify
java -version
```

---

#### Windows

**Use jenv (optional)**:
```cmd
# Install jenv
scoop install jenv

# List versions
jenv versions

# Set Java 25 for current directory
jenv local 25

# Or globally
jenv global 25
```

---

## Verification

### Verify Java Version

```bash
# Check Java version
java -version
```

**Expected**:
```
openjdk version "25.0.x"
```

**NOT**:
```
openjdk version "26.0.x"  ❌ TOO NEW
openjdk version "21.0.x"  ❌ TOO OLD
openjdk version "17.0.x"  ❌ TOO OLD
```

---

### Verify Maven Uses Java 25

```bash
# Check Maven's Java version
mvn -version
```

**Look for**:
```
Apache Maven 3.x.x
Java version: 25.0.x, vendor: Homebrew
```

**NOT**:
```
Java version: 17.0.x  ❌ WRONG
Java version: 21.0.x  ❌ WRONG
```

---

### Verify Build Works

```bash
# Navigate to project
cd /path/to/drools-microservice

# Try to build
mvn clean compile
```

**Success Output**:
```
[INFO] --- enforcer:3.6.2:enforce (enforce-java) @ drools-rule-engine ---
[INFO] Rule 0: org.apache.maven.enforcer.rules.version.RequireJavaVersion passed
[INFO] Rule 1: org.apache.maven.enforcer.rules.version.RequireMavenVersion passed
[INFO]
[INFO] --- compiler:3.15.0:compile (default-compile) @ drools-rule-engine ---
[INFO] Compiling 61 source files
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
```

**Failure Output** (if wrong Java):
```
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-enforcer-plugin:3.6.2:enforce
[ERROR] Rule 0: org.apache.maven.enforcer.rules.version.RequireJavaVersion failed with message:
[ERROR] ❌ Java 25 is required! Current version is not compatible.
```

---

## Troubleshooting

### Problem 1: Maven Still Uses Wrong Java

**Symptom**:
```bash
$ java -version
openjdk version "25.0.2"

$ mvn -version
Java version: 23.0.1  ❌
```

**Cause**: Maven may have its own JAVA_HOME

**Solution**:
```bash
# Set JAVA_HOME explicitly before running Maven
export JAVA_HOME=$(/usr/libexec/java_home -v 25)

# Verify
mvn -version

# Make permanent by adding to shell config
echo 'export JAVA_HOME=$(/usr/libexec/java_home -v 25)' >> ~/.zshrc
source ~/.zshrc
```

---

### Problem 2: "Java 25 not found"

**Symptom**:
```bash
$ /usr/libexec/java_home -v 25
Unable to find any JVMs matching version "25"
```

**Cause**: Java 25 not installed

**Solution**:
```bash
# macOS
brew install openjdk@25

# Verify
/usr/libexec/java_home -V
```

---

### Problem 3: Build Still Fails After Setup

**Symptom**:
```
[ERROR] Java 25 is required! Current version is not compatible.
```

**Debug Steps**:

1. **Verify JAVA_HOME is set**:
   ```bash
   echo $JAVA_HOME
   # Should show Java 25 path
   ```

2. **Verify java command**:
   ```bash
   which java
   java -version
   ```

3. **Check Maven's Java**:
   ```bash
   mvn -version | grep "Java version"
   ```

4. **Try explicit JAVA_HOME**:
   ```bash
   export JAVA_HOME=/opt/homebrew/Cellar/openjdk@25/25.0.2/libexec/openjdk.jdk/Contents/Home
   mvn clean compile
   ```

5. **Check for Maven wrapper**:
   ```bash
   # If mvnw exists, use it instead
   ./mvnw clean compile
   ```

---

### Problem 4: IntelliJ IDEA Uses Wrong Java

**Solution**:

1. File → Project Structure
2. Project Settings → Project
3. SDK: Select Java 25
4. Language level: 25
5. File → Settings → Build, Execution, Deployment → Build Tools → Maven
6. Maven home directory: Point to Maven 3.8+
7. JRE: Use Project JDK (Java 25)

---

### Problem 5: Eclipse Uses Wrong Java

**Solution**:

1. Window → Preferences
2. Java → Installed JREs
3. Add → Standard VM
4. Browse to Java 25 installation
5. Check the box to make it default
6. Project → Properties → Java Compiler
7. Enable project specific settings
8. Compiler compliance level: 25

---

### Problem 6: VS Code Uses Wrong Java

**Solution**:

Update `settings.json`:
```json
{
    "java.configuration.runtimes": [
        {
            "name": "JavaSE-25",
            "path": "/opt/homebrew/Cellar/openjdk@25/25.0.2/libexec/openjdk.jdk/Contents/Home",
            "default": true
        }
    ],
    "java.home": "/opt/homebrew/Cellar/openjdk@25/25.0.2/libexec/openjdk.jdk/Contents/Home"
}
```

---

## IDE Configuration

### IntelliJ IDEA (Recommended)

**Import Project**:
1. File → Open
2. Select `pom.xml`
3. Open as Project
4. IntelliJ will auto-detect Java 25 requirement

**Verify**:
1. File → Project Structure
2. Project SDK should be Java 25
3. Language level should be 25

**Maven Configuration**:
1. View → Tool Windows → Maven
2. Click "M" (Execute Maven Goal)
3. Run: `clean compile`
4. Should see "BUILD SUCCESS"

---

### VS Code

**Extensions Required**:
- Extension Pack for Java
- Maven for Java

**Configuration**:
1. Ctrl/Cmd + Shift + P
2. "Java: Configure Java Runtime"
3. Select Java 25 as default

**Build**:
1. Open integrated terminal
2. Run: `mvn clean compile`

---

### Eclipse

**Import**:
1. File → Import
2. Maven → Existing Maven Projects
3. Select project directory
4. Finish

**Configure**:
1. Project → Properties
2. Java Compiler → Compliance level: 17
3. Java Build Path → Libraries → Edit JRE → Select Java 25

---

## Docker Development

### No Java Installation Needed!

If you only use Docker for development, you don't need Java installed locally:

```bash
# Everything runs in container
docker-compose up -d

# Build in container
docker-compose exec app mvn clean package

# Or use Docker build
docker build -t drools-engine .
```

**Container Uses**: Amazon Corretto 25 (Alpine) - built into Dockerfile

**Benefits**:
- No local Java setup needed
- Consistent environment
- Matches production

---

## Quick Reference

### Commands

```bash
# List Java versions (macOS)
/usr/libexec/java_home -V

# Set Java 25 (macOS)
export JAVA_HOME=$(/usr/libexec/java_home -v 25)

# Set Java 25 (Linux)
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk

# Verify Java
java -version

# Verify Maven's Java
mvn -version | grep "Java version"

# Build project
mvn clean compile

# Use project script (temporary)
source ./set-java-env.sh
```

### Files

- **Setup Script**: `./set-java-env.sh`
- **Enforcer Config**: `pom.xml` → `maven-enforcer-plugin` (`enforce-java` execution, `requireJavaVersion` = `[25,26)`)
- **Shell Config**: `~/.zshrc` or `~/.bashrc`

---

## Summary

### Checklist

- [ ] Java 25 installed
- [ ] JAVA_HOME set to Java 25
- [ ] PATH updated
- [ ] `java -version` shows 25.x.x
- [ ] `mvn -version` shows Java 25
- [ ] `mvn clean compile` succeeds
- [ ] IDE configured for Java 25

### Support

If issues persist:
1. Check `troubleshooting.md`
2. Review build logs carefully
3. Verify environment variables: `echo $JAVA_HOME`
4. Try Docker development (no local Java needed)

---

**Last Updated**: 2026-05-09
**Related Docs**: 31-troubleshooting.md, 06-deployment.md, 08-configuration.md

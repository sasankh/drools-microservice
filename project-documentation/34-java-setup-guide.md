# Java 17 Setup Guide

**Version**: 1.0.0
**Last Updated**: 2026-02-19
**Requirement**: Java 17 (LTS) - Enforced by Maven

---

## Table of Contents

1. [Overview](#overview)
2. [Why Java 17?](#why-java-17)
3. [Installation](#installation)
4. [Environment Setup](#environment-setup)
5. [Verification](#verification)
6. [Troubleshooting](#troubleshooting)
7. [IDE Configuration](#ide-configuration)
8. [Docker Development](#docker-development)

---

## Overview

### Java 17 Requirement

This project **requires Java 17** specifically. The Maven Enforcer Plugin will automatically fail builds if using a different Java version.

**Critical**: As of 2026-02-19, the project enforces Java 17 at build time to prevent compatibility issues.

### What's Enforced

```xml
<!-- pom.xml -->
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-enforcer-plugin</artifactId>
    <version>3.3.0</version>
    <executions>
        <execution>
            <id>enforce-java</id>
            <goals><goal>enforce</goal></goals>
            <configuration>
                <rules>
                    <requireJavaVersion>
                        <version>[17,18)</version>
                        <message>❌ Java 17 is required!</message>
                    </requireJavaVersion>
                </rules>
            </configuration>
        </execution>
    </executions>
</plugin>
```

**Result**: Build fails with clear error message if Java 17 is not used.

---

## Why Java 17?

### Technical Reasons

1. **Spring Boot 3.2.5 Baseline**: Requires Java 17 minimum
2. **Drools 8.44.0.Final Compatibility**: Optimized for Java 17
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
- Untested with Drools 8.44.0
- Different GC behavior
- May introduce bugs

**Result**: Stick with Java 17 for guaranteed compatibility.

---

## Installation

### macOS (Homebrew)

**Install Java 17**:
```bash
# Install OpenJDK 17
brew install openjdk@17

# Link it (optional)
sudo ln -sfn /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk \
    /Library/Java/JavaVirtualMachines/openjdk-17.jdk

# Verify installation
/opt/homebrew/opt/openjdk@17/bin/java --version
```

**Expected Output**:
```
openjdk 17.0.x 2024-xx-xx
OpenJDK Runtime Environment Homebrew (build 17.0.x+x)
OpenJDK 64-Bit Server VM Homebrew (build 17.0.x+x, mixed mode, sharing)
```

---

### Linux (Ubuntu/Debian)

```bash
# Update package list
sudo apt update

# Install OpenJDK 17
sudo apt install openjdk-17-jdk

# Verify
java -version
```

---

### Linux (RHEL/CentOS/Fedora)

```bash
# Install OpenJDK 17
sudo dnf install java-17-openjdk-devel

# Or on older systems
sudo yum install java-17-openjdk-devel

# Verify
java -version
```

---

### Windows

**Option 1: Adoptium (Recommended)**
1. Download from https://adoptium.net/
2. Select:
   - Version: 17 (LTS)
   - JVM: HotSpot
   - Operating System: Windows
3. Run installer
4. Verify:
   ```cmd
   java -version
   ```

**Option 2: Oracle JDK**
1. Download from https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html
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
   JAVA_HOME: /opt/homebrew/Cellar/openjdk@17/17.0.13/libexec/openjdk.jdk/Contents/Home
   Java version:
openjdk version "17.0.13" 2024-10-15
OpenJDK Runtime Environment Homebrew (build 17.0.13+0)
OpenJDK 64-Bit Server VM Homebrew (build 17.0.13+0, mixed mode, sharing)

   Maven will use:
Java version: 17.0.13, vendor: Homebrew
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
# Java 17 for Drools Rule Engine
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
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
   - Variable value: `C:\Program Files\Java\jdk-17`
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
    17.0.13 (arm64) "Homebrew" - "OpenJDK 17.0.13" /opt/homebrew/Cellar/openjdk@17/17.0.13/...
    11.0.15 (arm64) "Homebrew" - "OpenJDK 11.0.15" /opt/homebrew/Cellar/openjdk@11/11.0.15/...
```

**Switch to Java 17**:
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
```

---

#### Linux

**Use update-alternatives**:
```bash
# List available Java versions
sudo update-alternatives --config java

# Select Java 17 from the list
# Example: Enter '2' to select java-17-openjdk

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

# Set Java 17 for current directory
jenv local 17

# Or globally
jenv global 17
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
openjdk version "17.0.x"
```

**NOT**:
```
openjdk version "23.0.x"  ❌ TOO NEW
openjdk version "11.0.x"  ❌ TOO OLD
```

---

### Verify Maven Uses Java 17

```bash
# Check Maven's Java version
mvn -version
```

**Look for**:
```
Apache Maven 3.x.x
Java version: 17.0.x, vendor: Homebrew
```

**NOT**:
```
Java version: 23.0.x  ❌ WRONG
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
[INFO] --- enforcer:3.3.0:enforce (enforce-java) @ drools-rule-engine ---
[INFO] Rule 0: org.apache.maven.enforcer.rules.version.RequireJavaVersion passed
[INFO] Rule 1: org.apache.maven.enforcer.rules.version.RequireMavenVersion passed
[INFO]
[INFO] --- compiler:3.11.0:compile (default-compile) @ drools-rule-engine ---
[INFO] Compiling 54 source files
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
```

**Failure Output** (if wrong Java):
```
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-enforcer-plugin:3.3.0:enforce
[ERROR] Rule 0: org.apache.maven.enforcer.rules.version.RequireJavaVersion failed with message:
[ERROR] ❌ Java 17 is required! Current version is not compatible.
```

---

## Troubleshooting

### Problem 1: Maven Still Uses Wrong Java

**Symptom**:
```bash
$ java -version
openjdk version "17.0.13"

$ mvn -version
Java version: 23.0.1  ❌
```

**Cause**: Maven may have its own JAVA_HOME

**Solution**:
```bash
# Set JAVA_HOME explicitly before running Maven
export JAVA_HOME=$(/usr/libexec/java_home -v 17)

# Verify
mvn -version

# Make permanent by adding to shell config
echo 'export JAVA_HOME=$(/usr/libexec/java_home -v 17)' >> ~/.zshrc
source ~/.zshrc
```

---

### Problem 2: "Java 17 not found"

**Symptom**:
```bash
$ /usr/libexec/java_home -v 17
Unable to find any JVMs matching version "17"
```

**Cause**: Java 17 not installed

**Solution**:
```bash
# macOS
brew install openjdk@17

# Verify
/usr/libexec/java_home -V
```

---

### Problem 3: Build Still Fails After Setup

**Symptom**:
```
[ERROR] Java 17 is required! Current version is not compatible.
```

**Debug Steps**:

1. **Verify JAVA_HOME is set**:
   ```bash
   echo $JAVA_HOME
   # Should show Java 17 path
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
   export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.13/libexec/openjdk.jdk/Contents/Home
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
3. SDK: Select Java 17
4. Language level: 17
5. File → Settings → Build, Execution, Deployment → Build Tools → Maven
6. Maven home directory: Point to Maven 3.8+
7. JRE: Use Project JDK (Java 17)

---

### Problem 5: Eclipse Uses Wrong Java

**Solution**:

1. Window → Preferences
2. Java → Installed JREs
3. Add → Standard VM
4. Browse to Java 17 installation
5. Check the box to make it default
6. Project → Properties → Java Compiler
7. Enable project specific settings
8. Compiler compliance level: 17

---

### Problem 6: VS Code Uses Wrong Java

**Solution**:

Update `settings.json`:
```json
{
    "java.configuration.runtimes": [
        {
            "name": "JavaSE-17",
            "path": "/opt/homebrew/Cellar/openjdk@17/17.0.13/libexec/openjdk.jdk/Contents/Home",
            "default": true
        }
    ],
    "java.home": "/opt/homebrew/Cellar/openjdk@17/17.0.13/libexec/openjdk.jdk/Contents/Home"
}
```

---

## IDE Configuration

### IntelliJ IDEA (Recommended)

**Import Project**:
1. File → Open
2. Select `pom.xml`
3. Open as Project
4. IntelliJ will auto-detect Java 17 requirement

**Verify**:
1. File → Project Structure
2. Project SDK should be Java 17
3. Language level should be 17

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
3. Select Java 17 as default

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
3. Java Build Path → Libraries → Edit JRE → Select Java 17

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

**Container Uses**: Amazon Corretto 17 (Alpine) - built into Dockerfile

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

# Set Java 17 (macOS)
export JAVA_HOME=$(/usr/libexec/java_home -v 17)

# Set Java 17 (Linux)
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk

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
- **Enforcer Config**: `pom.xml` lines 256-278
- **Shell Config**: `~/.zshrc` or `~/.bashrc`

---

## Summary

### Checklist

- [ ] Java 17 installed
- [ ] JAVA_HOME set to Java 17
- [ ] PATH updated
- [ ] `java -version` shows 17.x.x
- [ ] `mvn -version` shows Java 17
- [ ] `mvn clean compile` succeeds
- [ ] IDE configured for Java 17

### Support

If issues persist:
1. Check `troubleshooting.md`
2. Review build logs carefully
3. Verify environment variables: `echo $JAVA_HOME`
4. Try Docker development (no local Java needed)

---

**Last Updated**: 2026-02-19
**Related Docs**: troubleshooting.md, deployment.md, configuration.md

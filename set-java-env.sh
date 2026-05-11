#!/bin/bash
# Set Java 25 environment for Drools Rule Engine Microservice

# Find Java 25 installation
JAVA_25_HOME=$(/usr/libexec/java_home -v 25 2>/dev/null)

# Fallback: Homebrew installs openjdk@25 outside the system JavaVM directory
if [ -z "$JAVA_25_HOME" ] && [ -d "/opt/homebrew/Cellar/openjdk/25.0.2/libexec/openjdk.jdk/Contents/Home" ]; then
    JAVA_25_HOME="/opt/homebrew/Cellar/openjdk/25.0.2/libexec/openjdk.jdk/Contents/Home"
fi

if [ -z "$JAVA_25_HOME" ] || [ ! -d "$JAVA_25_HOME" ]; then
    echo "❌ ERROR: Java 25 not found!"
    echo "Please install Java 25:"
    echo "  brew install openjdk@25"
    echo "  # then symlink so /usr/libexec/java_home picks it up:"
    echo "  sudo ln -sfn /opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-25.jdk"
    exit 1
fi

# Set environment variables
export JAVA_HOME="$JAVA_25_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

# Verify
echo "✅ Java environment configured:"
echo "   JAVA_HOME: $JAVA_HOME"
echo "   Java version:"
java -version
echo ""
echo "   Maven will use:"
mvn -version | grep "Java version"

echo ""
echo "💡 To make this permanent, add the following to your ~/.zshrc:"
echo ""
echo "# Java 25 for Drools Rule Engine"
echo "export JAVA_HOME=\$(/usr/libexec/java_home -v 25)"
echo "export PATH=\"\$JAVA_HOME/bin:\$PATH\""
echo ""

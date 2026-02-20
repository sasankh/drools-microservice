#!/bin/bash
# Set Java 17 environment for Drools Rule Engine Microservice

# Find Java 17 installation
JAVA_17_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null)

if [ -z "$JAVA_17_HOME" ]; then
    echo "❌ ERROR: Java 17 not found!"
    echo "Please install Java 17:"
    echo "  brew install openjdk@17"
    exit 1
fi

# Set environment variables
export JAVA_HOME="$JAVA_17_HOME"
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
echo "# Java 17 for Drools Rule Engine"
echo "export JAVA_HOME=\$(/usr/libexec/java_home -v 17)"
echo "export PATH=\"\$JAVA_HOME/bin:\$PATH\""
echo ""

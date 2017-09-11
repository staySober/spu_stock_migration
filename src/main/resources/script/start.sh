#!/bin/bash

BASE_DIR=$(dirname $0)
LIB_DIR=$BASE_DIR/lib
CLASSPATH=lib/log4j-over-slf4j-1.7.7.jar:${BASE_DIR}/conf:$LIB_DIR/\*

JAVA_OPT_7="-cp ${CLASSPATH}"
# -javaagent:${BASE_DIR}/lib/aliapm-2.1.0.jar
JAVA_OPTS="${JAVA_OPTS}  ${JAVA_OPT_7}"

JAVA="java"

KEYWORD="com.yit.runner.MainMigration"

function startService {
   $JAVA $JAVA_OPTS $KEYWORD
   return 0;
}

function main {
   RETVAL=0
   startService
   exit $RETVAL
}

main $1
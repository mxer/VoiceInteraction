#!/bin/sh
java -cp js.jar org.mozilla.javascript.tools.debugger.Main -opt -1 run.js $*

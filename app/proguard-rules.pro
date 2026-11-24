# R8 rules for Shuttle release builds.

# The JNI bridge (src/main/cpp/shuttle_llm.cpp) finds these by name: keep the native methods
# and the callback the native code calls for each piece of the reply.
-keep class app.shuttle.assistant.llm.LlamaEngine { native <methods>; }
-keep interface app.shuttle.assistant.llm.LlamaEngine$PieceSink { *; }

# Klarna Erlang Hackathon 2021

Getting Erjang to work on a newer JDK (11) and OTP (23)

## Building erjang

Start out by running `ant jar`.

1. Broken perl-invocation: `[exec] Can't locate ErjIG_Util.pm in @INC (you may need to install the ErjIG_Util module) (@INC contains: /etc/perl /usr/local/lib/x86_64-linux-gnu/perl/5.30.0 /usr/local/share/perl/5.30.0 /usr/lib/x86_64-linux-gnu/perl5/5.30 /usr/share/perl5 /usr/lib/x86_64-linux-gnu/perl/5.30 /usr/share/perl/5.30 /usr/local/lib/site_perl /usr/lib/x86_64-linux-gnu/perl-base) at ErjIG_Main.pl line 3.`

This seems to be a missing `-I.` in the perl invocation.

2. Missing library in Eclipse `.classpath`: `Project 'erjang' is
   missing required library: 'lib/asm-all-4.1.jar'`. Workaround for
   now; remove the library from the .classpath, but I guess this will
   break later.

2. Refresh Eclipse project
3. `kilim.jar` cannot be used due to weird error.

```
[javac] /home/jesperes/dev/erjang/src/main/java/erjang/EHandle.java:25: error: cannot access Task
[javac] import kilim.Task;
[javac]             ^
[javac]   bad class file: /home/jesperes/dev/erjang/lib/kilim.jar(/kilim/Task.class)
[javac]     class file contains malformed variable arity method: invoke(Method,Object,Object[],Fiber)
[javac]     Please remove or make sure it appears in the correct subdirectory of the classpath.
```

Seems to be related to:
https://github.com/twitter/util/commit/7e5024bd42327e6acae7954ac9b212b5ed51610d

Suspecting that kilim is out-of-date and does not work properly with Java 11.

## Task 1: Update kilim

1. Clone kilim from https://github.com/kilim/kilim and run `ant jar`.
2. Then `cp kilim/target/kilim.jar ../erjang/lib/`

Dropping in the new kilim gives:

`The type org.objectweb.asm.ClassVisitor cannot be resolved. It is indirectly referenced from required .class files	Compiler.java	/erjang/src/main/java/erjang/beam	line 195	Java Problem`

This is presumably caused by the missing asm-all-4.1.jar. Try to download from
https://repo1.maven.org/maven2/org/ow2/asm/asm-all/4.1/.

... more compilation errors ensue ...

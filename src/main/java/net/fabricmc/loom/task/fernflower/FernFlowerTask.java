/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016, 2017, 2018 FabricMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.fabricmc.loom.task.fernflower;

import net.fabricmc.loom.task.AbstractDecompileTask;
import net.fabricmc.loom.task.ForkingJavaExecTask;
import net.fabricmc.loom.util.ConsumingOutputStream;

import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.internal.logging.progress.ProgressLogger;
import org.gradle.internal.logging.progress.ProgressLoggerFactory;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.process.ExecResult;

import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger.Severity;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;

import java.io.OutputStream;
import java.io.PrintStream;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.function.Supplier;

/**
 * Created by covers1624 on 9/02/19.
 */
public class FernFlowerTask extends AbstractDecompileTask implements ForkingJavaExecTask {

    private boolean noFork = false;
    private int numThreads = Runtime.getRuntime().availableProcessors();

    @TaskAction
    public void doTask() throws Throwable {
        Map<String, Object> options = new HashMap<>();
        options.put(IFernflowerPreferences.DECOMPILE_GENERIC_SIGNATURES, "1");
        options.put(IFernflowerPreferences.BYTECODE_SOURCE_MAPPING, "1");
        options.put(IFernflowerPreferences.INDENT_STRING, "\t"); //Use a tab not three spaces :|
		options.put(IFernflowerPreferences.INCLUDE_ENTIRE_CLASSPATH, "1");
        options.put(IFernflowerPreferences.LOG_LEVEL, "trace");
        getLogging().captureStandardOutput(LogLevel.LIFECYCLE);

        List<String> args = new ArrayList<>();

        options.forEach((k, v) -> args.add(MessageFormat.format("-{0}={1}", k, v)));
        args.add(getInput().getAbsolutePath());
        args.add("-o=" + getOutput().getAbsolutePath());
        if (getLineMapFile() != null) {
            args.add("-l=" + getLineMapFile().getAbsolutePath());
        }
        args.add("-t=" + getNumThreads());

        //TODO, Decompiler breaks on jemalloc, J9 module-info.class?
        getLibraries().forEach(f -> args.add("-e=" + f.getAbsolutePath()));

        ServiceRegistry registry = ((ProjectInternal) getProject()).getServices();
        ProgressLoggerFactory factory = registry.get(ProgressLoggerFactory.class);
        ProgressLogger progressGroup = factory.newOperation(getClass()).setDescription("Decompile");
        Supplier<ProgressLogger> loggerFactory = () -> {
            ProgressLogger pl = factory.newOperation(getClass(), progressGroup);
            pl.setDescription("decompile worker");
            pl.started();
            return pl;
        };
        Stack<ProgressLogger> freeLoggers = new Stack<>();
        Map<String, ProgressLogger> inUseLoggers = new HashMap<>();

        OutputStream stdOutput = new ConsumingOutputStream(line -> {
            if (line.startsWith("Listening for transport")) {
                System.out.println(line);
                return;
            }

            int sepIdx = line.indexOf("::");
            if (sepIdx < 1) {
            	getLogger().error("Unprefixed line: " + line);
            	return;
            }
            String id = line.substring(0, sepIdx).trim();
            String data = line.substring(sepIdx + 2).trim();

            ProgressLogger logger = inUseLoggers.get(id);

            String[] segs = data.split(" ");
            if (segs[0].equals("waiting")) {
                if (logger != null) {
                    logger.progress("Idle..");
                    inUseLoggers.remove(id);
                    freeLoggers.push(logger);
                }
            } else {
                if (logger == null) {
                    if (!freeLoggers.isEmpty()) {
                        logger = freeLoggers.pop();
                    } else {
                        logger = loggerFactory.get();
                    }
                    inUseLoggers.put(id, logger);
                }

                if (data.startsWith(Severity.INFO.prefix)) {
                	logger.progress(data.substring(Severity.INFO.prefix.length()));
                } else if (data.startsWith(Severity.TRACE.prefix)) {
                	logger.progress(data.substring(Severity.TRACE.prefix.length()));
                } else if (data.startsWith(Severity.WARN.prefix)) {
                	getLogger().warn(data.substring(Severity.WARN.prefix.length()));
                } else {
                	getLogger().error(data.substring(Severity.ERROR.prefix.length()));
                }
            }
        });
        OutputStream errOutput = System.err;

        try {
	        progressGroup.started();

	        if (!isNoFork()) {
		        ExecResult result = javaexec(spec -> {
		            spec.setMain(ForkedFFExecutor.class.getName());
		            spec.jvmArgs("-Xms200m", "-Xmx3G");
		            spec.setArgs(args);
		            spec.setErrorOutput(errOutput);
		            spec.setStandardOutput(stdOutput);
		        });

		        result.rethrowFailure();
		        result.assertNormalExitValue();
	        } else {
	        	ForkedFFExecutor.main(args.toArray(new String[0]), new PrintStream(stdOutput, true), new PrintStream(errOutput, true));
	        }
        } finally {
	        inUseLoggers.values().forEach(ProgressLogger::completed);
	        freeLoggers.forEach(ProgressLogger::completed);
	        progressGroup.completed();
        }
    }

    //@formatter:off
    @Internal public int getNumThreads() { return numThreads; }
    @Internal public boolean isNoFork() { return noFork; }
    public void setNoFork(boolean noFork) { this.noFork = noFork; }
    public void setNumThreads(int numThreads) { this.numThreads = numThreads;
    if (numThreads > 1) getLogger().warn("Using multiple threads is unsupported with ForgeFlower");
    }
    //@formatter:on
}

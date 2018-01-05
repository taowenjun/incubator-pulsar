/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.functions.runtime.worker;

import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.apache.distributedlog.api.namespace.Namespace;
import org.apache.pulsar.functions.runtime.container.FunctionContainerFactory;
import org.apache.pulsar.functions.runtime.spawner.LimitsConfig;
import org.apache.pulsar.functions.runtime.spawner.Spawner;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@Data
@Setter
@Getter
@EqualsAndHashCode
@ToString
@Slf4j
public class FunctionActioner implements AutoCloseable {

    private final WorkerConfig workerConfig;
    private final LimitsConfig limitsConfig;
    private final FunctionContainerFactory functionContainerFactory;
    private final Namespace dlogNamespace;
    private LinkedBlockingQueue<FunctionAction> actionQueue;
    private volatile boolean running;
    private Thread actioner;

    public FunctionActioner(WorkerConfig workerConfig, LimitsConfig limitsConfig,
                            FunctionContainerFactory functionContainerFactory,
                            Namespace dlogNamespace,
                            LinkedBlockingQueue<FunctionAction> actionQueue) {
        this.workerConfig = workerConfig;
        this.limitsConfig = limitsConfig;
        this.functionContainerFactory = functionContainerFactory;
        this.dlogNamespace = dlogNamespace;
        this.actionQueue = actionQueue;
        actioner = new Thread(() -> {
            log.info("Starting Actioner Thread...");
            while(running) {
                try {
                    FunctionAction action = actionQueue.poll(1, TimeUnit.SECONDS);
                    if (action == null) continue;
                    if (action.getAction() == FunctionAction.Action.START) {
                        startFunction(action.getFunctionMetaData());
                    } else {
                        stopFunction(action.getFunctionMetaData());
                    }
                } catch (InterruptedException ex) {
                }
            }
        });
        actioner.setName("FunctionActionerThread");
    }

    public void start() {
        this.running = true;
        actioner.start();
    }

    @Override
    public void close() {
        running = false;
    }

    public void join() throws InterruptedException {
        actioner.join();
    }

    private boolean startFunction(FunctionMetaData functionMetaData) {
        log.info("Starting function {} ...", functionMetaData.getFunctionConfig().getName());
        try {
            File pkgDir = new File(
                    workerConfig.getDownloadDirectory(),
                    StringUtils.join(
                            new String[]{
                                    functionMetaData.getFunctionConfig().getTenant(),
                                    functionMetaData.getFunctionConfig().getNamespace(),
                                    functionMetaData.getFunctionConfig().getName(),
                            },
                            File.separatorChar));
            pkgDir.mkdirs();

            File pkgFile = new File(pkgDir, new File(functionMetaData.getPackageLocation().getPackagePath()).getName());
            if (!pkgFile.exists()) {
                log.info("Function package file {} doesn't exist, downloading from {}",
                        pkgFile, functionMetaData.getPackageLocation());
                if (!Utils.downloadFromBookkeeper(
                        dlogNamespace,
                        new FileOutputStream(pkgFile),
                        functionMetaData.getPackageLocation().getPackagePath())) {
                    log.error("Not able to download {} to {}", functionMetaData.getPackageLocation().getPackagePath(), pkgFile.getPath());
                    return false;
                }
            }
            log.info("Done downloading");
            Spawner spawner = Spawner.createSpawner(functionMetaData.getFunctionConfig(), limitsConfig,
                    pkgFile.getAbsolutePath(), functionContainerFactory);
            functionMetaData.setSpawner(spawner);
            spawner.start();
            return true;
        } catch (Exception ex) {
            log.error("Function {} failed to start", functionMetaData.getFunctionConfig().getName(), ex);
            return false;
        }
    }

    private boolean stopFunction(FunctionMetaData functionMetaData) {
        log.info("Stopping function {}...", functionMetaData.getFunctionConfig().getName());
        if (functionMetaData.getSpawner() != null) {
            functionMetaData.getSpawner().close();
            functionMetaData.setSpawner(null);
            return true;
        }
        return false;
    }
}
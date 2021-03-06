/* *********************************************************************** *
 * project: org.matsim.*
 * EditRoutesTest.java
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2019 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */


package org.matsim.run;

import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.OutputDirectoryLogging;
import org.matsim.episim.EpisimConfigGroup;
import org.matsim.episim.EpisimConfigGroup.FacilitiesHandling;
import org.matsim.episim.policy.FixedPolicy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @author smueller
 */
public class RunParallel {

    private static final int MYTHREADS = 4;

    public static void main(String[] args) {

        ExecutorService executor = Executors.newFixedThreadPool(MYTHREADS);
        List<Long> pt = Arrays.asList(1000L, 10L, 20L, 30L);
        List<Long> work = Arrays.asList(1000L, 10L, 20L, 30L);
        List<Long> leisure = Arrays.asList(1000L, 10L, 20L, 30L);

        List<Long> otherExceptHome = Arrays.asList(1000L, 10L, 20L, 30L);

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (long p : pt) {
            for (long w : work) {
                for (long l : leisure) {
                    for (long o : otherExceptHome) {
                        futures.add(
                                CompletableFuture.runAsync(new MyRunnable(p, w, l, o), executor)
                        );
                    }
                }
            }
        }

        // Wait for all futures to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        System.out.println("\nFinished all threads");

        executor.shutdown();
    }

    public static class MyRunnable implements Runnable {
        private final long p;
        private final long w;
        private final long l;
        private final long o;

        MyRunnable(long p, long w, long l, long o) {
            this.p = p;
            this.w = w;
            this.l = l;
            this.o = o;

        }

        @Override
        public void run() {

            OutputDirectoryLogging.catchLogEntries();

            Config config = ConfigUtils.createConfig(new EpisimConfigGroup());
            EpisimConfigGroup episimConfig = ConfigUtils.addOrGetModule(config, EpisimConfigGroup.class);

            episimConfig.setInputEventsFile("../snzDrt220.0.events.reduced.xml.gz");
            episimConfig.setFacilitiesHandling(FacilitiesHandling.snz);

            episimConfig.setSampleSize(0.25);
            episimConfig.setCalibrationParameter(0.002);

            RunEpisim.addDefaultParams(episimConfig);

            episimConfig.getOrAddContainerParams("pt")
                    .setContactIntensity(10.0);

            episimConfig.setPolicyConfig(FixedPolicy.config()
                    .shutdown(this.p, "pt")
                    .shutdown(this.o, "business", "edu", "errands", "shopping")
                    .shutdown(this.l, "leisure")
                    .shutdown(this.w, "work")
                    .build()
            );

            config.controler().setOutputDirectory("output/" + p + "-" + w + "-" + l + "-" + o);

            try {
                RunEpisim.runSimulation(config, 100);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}

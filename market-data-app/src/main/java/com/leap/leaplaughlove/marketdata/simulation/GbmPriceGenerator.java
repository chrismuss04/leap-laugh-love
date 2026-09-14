package com.leap.leaplaughlove.marketdata.simulation;

import java.util.random.RandomGenerator;

public final class GbmPriceGenerator {

    private GbmPriceGenerator() {
    }

    /**
     * One discretized step of Geometric Brownian Motion:
     * next = prev * exp((drift - volatility^2 / 2) * dt + volatility * sqrt(dt) * Z), Z ~ N(0,1).
     */
    public static double nextPrice(double previousPrice, double drift, double volatility, double dt, RandomGenerator random) {
        if (previousPrice <= 0) {
            throw new IllegalArgumentException("previousPrice must be positive");
        }
        if (dt <= 0) {
            throw new IllegalArgumentException("dt must be positive");
        }
        double z = random.nextGaussian();
        double exponent = (drift - 0.5 * volatility * volatility) * dt + volatility * Math.sqrt(dt) * z;
        return previousPrice * Math.exp(exponent);
    }
}

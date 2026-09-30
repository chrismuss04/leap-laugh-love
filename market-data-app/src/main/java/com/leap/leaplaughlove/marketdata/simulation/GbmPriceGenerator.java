package com.leap.leaplaughlove.marketdata.simulation;

import java.util.random.RandomGenerator;

/**
 * Generates simulated prices using discretized Geometric Brownian Motion.
 */
public final class GbmPriceGenerator {

    private GbmPriceGenerator() {
    }

    /**
     * Computes one discretized step of Geometric Brownian Motion:
     * next = prev * exp((drift - volatility^2 / 2) * dt + volatility * sqrt(dt) * Z), Z ~ N(0,1).
     * @param previousPrice the price at the start of the step; must be positive
     * @param drift the annualized drift
     * @param volatility the annualized volatility
     * @param dt the time step, in years; must be positive
     * @param random the random generator used to draw the standard normal shock
     * @return the simulated price after the step
     * @throws IllegalArgumentException if previousPrice or dt is not positive
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

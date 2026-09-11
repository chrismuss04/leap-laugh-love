package com.leap.leaplaughlove.marketdata.simulation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("GbmPriceGenerator Unit Tests")
class GbmPriceGeneratorTest {

    private RandomGenerator seededRandom(long seed) {
        return new Random(seed);
    }

    @Test
    @DisplayName("same seed and inputs produce the same next price (deterministic)")
    void testDeterministicUnderFixedSeed() {
        double a = GbmPriceGenerator.nextPrice(100.0, 0.05, 0.2, 1.0 / 365, seededRandom(42L));
        double b = GbmPriceGenerator.nextPrice(100.0, 0.05, 0.2, 1.0 / 365, seededRandom(42L));

        assertEquals(a, b, 1e-12);
    }

    @Test
    @DisplayName("zero volatility follows the pure drift path: next = prev * exp(drift * dt)")
    void testZeroVolatilityIsPureDrift() {
        double prev = 100.0;
        double drift = 0.10;
        double dt = 1.0;

        double next = GbmPriceGenerator.nextPrice(prev, drift, 0.0, dt, seededRandom(1L));

        assertEquals(prev * Math.exp(drift * dt), next, 1e-9);
    }

    @Test
    @DisplayName("price stays strictly positive across many steps")
    void testPriceStaysPositive() {
        double price = 50.0;
        RandomGenerator random = seededRandom(7L);
        for (int i = 0; i < 100_000; i++) {
            price = GbmPriceGenerator.nextPrice(price, 0.08, 0.6, 1.0 / (365 * 24 * 60 * 60), random);
            assertTrue(price > 0, "price must stay positive, was " + price + " at step " + i);
        }
    }

    @Test
    @DisplayName("non-positive previous price is rejected")
    void testRejectsNonPositivePreviousPrice() {
        assertThrows(IllegalArgumentException.class,
                () -> GbmPriceGenerator.nextPrice(0.0, 0.05, 0.2, 1.0, seededRandom(1L)));
        assertThrows(IllegalArgumentException.class,
                () -> GbmPriceGenerator.nextPrice(-10.0, 0.05, 0.2, 1.0, seededRandom(1L)));
    }

    @Test
    @DisplayName("non-positive dt is rejected")
    void testRejectsNonPositiveDt() {
        assertThrows(IllegalArgumentException.class,
                () -> GbmPriceGenerator.nextPrice(100.0, 0.05, 0.2, 0.0, seededRandom(1L)));
    }
}

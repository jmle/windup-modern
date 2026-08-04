package com.example.demo;

import javax.ejb.Stateless;
import javax.ejb.LocalBean;

/**
 * A stateless EJB demonstrating javax.ejb usage that should be
 * flagged for migration to jakarta.ejb.
 */
@Stateless
@LocalBean
public class DemoApplication {

    public String getGreeting() {
        return "Hello from DemoApplication";
    }

    public int computeSum(int a, int b) {
        return a + b;
    }
}

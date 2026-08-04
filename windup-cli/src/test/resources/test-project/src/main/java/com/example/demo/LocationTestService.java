package com.example.demo;

import com.example.legacy.LegacyService;
import com.example.legacy.LegacyException;
import com.example.legacy.LegacyInterface;
import com.example.legacy.LegacyAnnotation;
import com.example.legacy.LegacyParent;
import com.example.legacy.LegacyEvent;

@LegacyAnnotation
public class LocationTestService extends LegacyParent implements LegacyInterface {

    private LegacyService service;

    public LegacyEvent getEvent() {
        return null;
    }

    public void process(LegacyService param) throws LegacyException {
        LegacyService local = new LegacyService();

        if (local instanceof LegacyService) {
            LegacyService.create("test");
        }

        try {
            throw new LegacyException("error");
        } catch (LegacyException e) {
            e.printStackTrace();
        }
    }
}

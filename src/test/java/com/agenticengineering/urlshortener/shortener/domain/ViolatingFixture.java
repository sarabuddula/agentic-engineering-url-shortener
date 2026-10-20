package com.agenticengineering.urlshortener.shortener.domain;

import com.agenticengineering.urlshortener.orchestration.ViolatingFixtureTarget;
import org.springframework.stereotype.Component;

/**
 * T009 sacrificial fixture: a domain class that imports Spring and an orchestration type, so both architecture rules
 * report a violation. Deleted in T010.
 */
@Component
public class ViolatingFixture {

    private final ViolatingFixtureTarget target = new ViolatingFixtureTarget();
}

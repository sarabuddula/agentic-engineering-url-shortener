package com.agenticengineering.urlshortener.orchestration.engine;

/** What an executing stage knows about itself; extended with run inputs by the engine (T053/T054). */
public record StageContext(String node) {
}

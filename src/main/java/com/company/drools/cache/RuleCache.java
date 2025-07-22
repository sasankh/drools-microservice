package com.company.drools.cache;

import com.company.drools.core.model.Rule;
import java.util.List;
import java.util.Optional;

/**
 * Interface for rule caching implementations.
 * Supports multiple cache backends (LRU, Redis, etc.)
 */
public interface RuleCache {

  /**
   * Retrieves a cached rule by ID.
   * 
   * @param ruleId the rule identifier
   * @return Optional containing the rule if cached, empty otherwise
   */
  Optional<Rule> get(String ruleId);

  /**
   * Stores a rule in the cache.
   * 
   * @param rule the rule to cache
   */
  void put(Rule rule);

  /**
   * Removes a rule from the cache.
   * 
   * @param ruleId the rule identifier to remove
   */
  void remove(String ruleId);

  /**
   * Checks if a rule exists in the cache.
   * 
   * @param ruleId the rule identifier
   * @return true if the rule is cached, false otherwise
   */
  boolean contains(String ruleId);

  /**
   * Clears all cached rules.
   */
  void clear();

  /**
   * Returns the number of cached rules.
   * 
   * @return the current cache size
   */
  long size();

  /**
   * Returns the maximum cache capacity.
   * 
   * @return the maximum number of rules this cache can hold
   */
  long maxSize();

  /**
   * Returns all cached rule IDs.
   * 
   * @return list of cached rule identifiers
   */
  List<String> getCachedRuleIds();

  /**
   * Returns cache statistics.
   * 
   * @return cache statistics object
   */
  CacheStatistics getStatistics();

  /**
   * Warms up the cache with the provided rules.
   * 
   * @param rules the rules to pre-load into cache
   */
  void warmUp(List<Rule> rules);

  /**
   * Evicts least recently used rules if cache is full.
   * This method is typically called internally by cache implementations.
   */
  void evictIfNeeded();

  /**
   * Checks if the cache is enabled.
   * 
   * @return true if caching is enabled, false otherwise
   */
  boolean isEnabled();
}
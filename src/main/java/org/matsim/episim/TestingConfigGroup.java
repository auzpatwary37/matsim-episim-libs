package org.matsim.episim;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import org.matsim.core.config.ReflectiveConfigGroup;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Config option specific to testing and measures performed in {@link org.matsim.episim.model.ProgressionModel}.
 */
public class TestingConfigGroup extends ReflectiveConfigGroup {

	private static final Splitter.MapSplitter SPLITTER = Splitter.on(";").withKeyValueSeparator("=");
	private static final Joiner.MapJoiner JOINER = Joiner.on(";").withKeyValueSeparator("=");

	private static final String CAPACITY = "testingCapacity";
	private static final String FALSE_POSITIVE_RATE = "falsePositiveRate";
	private static final String FALSE_NEGATIVE_RATE = "falseNegativeRate";
	private static final String ACTIVITIES = "activities";
	private static final String STRATEGY = "strategy";

	private static final String GROUPNAME = "episimTesting";

	/**
	 * Amount of tests per day.
	 */
	private final Map<LocalDate, Integer> testingCapacity = new TreeMap<>();

	/**
	 * Probability that a not infected person is reported as positive.
	 */
	private double falsePositiveRate = 0.97;

	/**
	 * Probability that an infected person is not identified.
	 */
	private double falseNegativeRate = 0.90;

	/**
	 * Tracing and containment strategy.
	 */
	private Strategy strategy = Strategy.NONE;

	/**
	 * Activities to test when using {@link Strategy#ACTIVITIES}.
	 */
	private final Set<String> activities = new HashSet<>();

	/**
	 * Default constructor.
	 */
	public TestingConfigGroup() {
		super(GROUPNAME);
	}

	/**
	 * Sets the tracing capacity for the whole simulation period.
	 *
	 * @param capacity number of persons to trace per day.
	 * @see #setTestingCapacity_pers_per_day(int) (Map)
	 */
	public void setTestingCapacity_pers_per_day(int capacity) {
		setTestingCapacity_pers_per_day(Map.of(LocalDate.of(1970, 1, 1), capacity));
	}

	/**
	 * Sets the tracing capacity for individual days. If a day has no entry the previous will be still valid.
	 *
	 * @param capacity map of dates to changes in capacity.
	 */
	public void setTestingCapacity_pers_per_day(Map<LocalDate, Integer> capacity) {
		testingCapacity.clear();
		testingCapacity.putAll(capacity);
	}

	public Map<LocalDate, Integer> getTestingCapacity() {
		return testingCapacity;
	}

	@StringSetter(CAPACITY)
	void setTestingCapacity(String capacity) {

		Map<String, String> map = SPLITTER.split(capacity);
		setTestingCapacity_pers_per_day(map.entrySet().stream().collect(Collectors.toMap(
				e -> LocalDate.parse(e.getKey()), e -> Integer.parseInt(e.getValue())
		)));
	}

	@StringGetter(CAPACITY)
	String getTracingCapacityString() {
		return JOINER.join(testingCapacity);
	}

	@StringGetter(FALSE_POSITIVE_RATE)
	public double getFalsePositiveRate() {
		return falsePositiveRate;
	}

	@StringSetter(FALSE_POSITIVE_RATE)
	public void setFalsePositiveRate(double falsePositiveRate) {
		this.falsePositiveRate = falsePositiveRate;
	}

	@StringGetter(FALSE_NEGATIVE_RATE)
	public double getFalseNegativeRate() {
		return falseNegativeRate;
	}

	@StringSetter(FALSE_NEGATIVE_RATE)
	public void setFalseNegativeRate(double falseNegativeRate) {
		this.falseNegativeRate = falseNegativeRate;
	}

	public void setActivities(List<String> activities) {
		this.activities.clear();
		this.activities.addAll(activities);
	}

	public Set<String> getActivities() {
		return activities;
	}

	@StringSetter(ACTIVITIES)
	void setActivitiesString(String activitiesString) {
		setActivities(Splitter.on(",").splitToList(activitiesString));
	}

	@StringGetter(ACTIVITIES)
	String getActivitiesString() {
		return Joiner.on(",").join(activities);
	}

	@StringGetter(STRATEGY)
	public Strategy getStrategy() {
		return strategy;
	}

	@StringSetter(STRATEGY)
	public void setStrategy(Strategy strategy) {
		this.strategy = strategy;
	}

	public enum Strategy {

		/**
		 * No tracing.
		 */
		NONE,

		/**
		 * Test with at fixed days.
		 */
		FIXED_DAYS,

		/**
		 * Test persons that have certain activity at each day.
		 */
		ACTIVITIES
	}

}

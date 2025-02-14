/*-
 * #%L
 * MATSim Episim
 * %%
 * Copyright (C) 2020 matsim-org
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */
package org.matsim.episim;

import com.google.common.annotations.Beta;
import it.unimi.dsi.fastutil.objects.Object2DoubleLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.episim.events.EpisimInfectionEvent;
import org.matsim.episim.events.EpisimInitialInfectionEvent;
import org.matsim.episim.events.EpisimPersonStatusEvent;
import org.matsim.episim.events.EpisimPotentialInfectionEvent;
import org.matsim.episim.model.VaccinationType;
import org.matsim.episim.model.VirusStrain;
import org.matsim.facilities.ActivityFacility;
import org.matsim.utils.objectattributes.attributable.Attributable;
import org.matsim.utils.objectattributes.attributable.Attributes;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.time.DayOfWeek;
import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static org.matsim.episim.EpisimUtils.readChars;
import static org.matsim.episim.EpisimUtils.writeChars;

/**
 * Persons current state in the simulation.
 */
public final class EpisimPerson implements Attributable {

	private final Id<Person> personId;
	private final EpisimReporting reporting;
	// This data structure is quite slow: log n costs, which should be constant...
	private final Attributes attributes;

	/**
	 * Whole trajectory over all days of the week.
	 * Entries contain the starting time of activities and the performed activity.
	 */
	private final List<PerformedActivity> trajectory = new ArrayList<>();

	/**
	 * The position in the trajectory at the start for each day of the week.
	 */
	private final int[] startOfDay = new int[7];

	/**
	 * The position in the trajectory for the end of the day.
	 */
	private final int[] endOfDay = new int[7];

	/**
	 * The first visited {@link org.matsim.facilities.ActivityFacility} for each day.
	 * Can be null if person does not start in a container.
	 */
	private final Id<ActivityFacility>[] firstFacilityId = new Id[7];

	/**
	 * The last visited {@link org.matsim.facilities.ActivityFacility} for each day.
	 * This is null if a person does not end its day in a container.
	 */
	private final Id<ActivityFacility>[] lastFacilityId = new Id[7];
	// Fields above are initialized from the sim and not persisted

	/**
	 * Whether person stays in container at the end of a day.
	 */
	private final boolean[] staysInContainer = new boolean[7];

	/**
	 * Traced contacts with other persons.
	 */
	private final Object2DoubleMap<EpisimPerson> traceableContactPersons = new Object2DoubleLinkedOpenHashMap<>(4);

	/**
	 * Stores first time of status changes to specific type.
	 */
	private final EnumMap<DiseaseStatus, Double> statusChanges = new EnumMap<>(DiseaseStatus.class);

	/**
	 * Total spent time during activities.
	 */
	private final Object2DoubleMap<String> spentTime = new Object2DoubleOpenHashMap<>(4);

	/**
	 * Activity participation of the current day. Same length as {@link #trajectory}
	 */
	private BitSet activityParticipation;

	/**
	 * In the parallel version of the {@link ReplayHandler}, the infections
	 * are not happen in a chronically order. The earliestInfections
	 * check therefore, that the first infection is valued as the important
	 * infection
	 */
	private EpisimInfectionEvent earliestInfection = null;

	/**
	 * List of all potential infection that happened during the day.
	 */
	private List<EpisimPotentialInfectionEvent> potentialInfectionEvents = new ArrayList<>();

	/**
	 * The facility where the person got infected. Can be null if person was initially infected.
	 */
	private Id<ActivityFacility> infectionContainer = null;

	/**
	 * The infection type when the person got infected. Can be null if person was initially infected.
	 */
	private String infectionType = null;

	/**
	 * Current {@link DiseaseStatus}.
	 */
	private DiseaseStatus status = DiseaseStatus.susceptible;
	/**
	 * Current {@link QuarantineStatus}.
	 */
	private QuarantineStatus quarantineStatus = QuarantineStatus.no;

	/**
	 * Strain of the virus the person was infected with.
	 */
	private VirusStrain virusStrain = VirusStrain.SARS_CoV_2;

	/**
	 * Current {@link VaccinationStatus}.
	 */
	private VaccinationStatus vaccinationStatus = VaccinationStatus.no;

	/**
	 * Current status for re-vaccination.
	 */
	private VaccinationStatus reVaccinationStatus = VaccinationStatus.no;

	/**
	 * Current {@link TestStatus}.
	 */
	private TestStatus testStatus = TestStatus.untested;

	/**
	 * Iteration when this person was vaccinated. Negative if person was never vaccinated.
	 */
	private int vaccinationDate = -1;

	/**
	 * Iteration when this person got into quarantine. Negative if person was never quarantined.
	 */
	private int quarantineDate = -1;

	/**
	 * Iteration when this person was tested. Negative if person was never tested.
	 */
	private int testDate = -1;

	/**
	 * How many times a person did go through the infected -> recovered cycle.
	 */
	private int numInfections = 0;

	/**
	 * Age of the person in years.
	 */
	private final int age;

	/**
	 * Whether this person can be traced.
	 */
	private boolean traceable;

	/**
	 * Whether this person can be vaccinated.
	 */
	private boolean vaccinable = true;

	/**
	 * Received vaccination type.
	 */
	private VaccinationType vaccinationType = VaccinationType.generic;

	/**
	 * Individual susceptibility of a person.
	 */
	private double susceptibility = 1;

	/**
	 * Lookup age from attributes.
	 */
	private static int getAge(Attributes attrs) {
		int age = -1;

		for (String attr : attrs.getAsMap().keySet()) {
			if (attr.contains("age")) {
				age = Integer.parseInt(attrs.getAttribute(attr).toString());
				break;
			}
		}

		return age;
	}

	public List<PerformedActivity> getTrajectory() {
		return trajectory;
	}

	EpisimPerson(Id<Person> personId, Attributes attrs, EpisimReporting reporting) {
		this(personId, attrs, true, reporting);
	}

	EpisimPerson(Id<Person> personId, Attributes attrs, boolean traceable, EpisimReporting reporting) {
		this.personId = personId;
		this.attributes = attrs;
		this.traceable = traceable;
		this.age = getAge(attrs);
		this.reporting = reporting;
	}

	/**
	 * Reads persons state from stream.
	 *
	 * @param persons map of all persons in the simulation
	 */
	void read(ObjectInput in, Map<Id<Person>, EpisimPerson> persons) throws IOException {

		int n = in.readInt();
		traceableContactPersons.clear();
		for (int i = 0; i < n; i++) {
			Id<Person> id = Id.create(readChars(in), Person.class);
			traceableContactPersons.put(persons.get(id), in.readDouble());
		}

		n = in.readInt();
		statusChanges.clear();
		for (int i = 0; i < n; i++) {
			int status = in.readInt();
			statusChanges.put(DiseaseStatus.values()[status], in.readDouble());
		}

		if (in.readBoolean()) {
			infectionContainer = Id.create(readChars(in), ActivityFacility.class);
		}

		if (in.readBoolean()) {
			infectionType = readChars(in);
		}

		n = in.readInt();
		spentTime.clear();
		for (int i = 0; i < n; i++) {
			String act = readChars(in);
			spentTime.put(act, in.readDouble());
		}

		status = DiseaseStatus.values()[in.readInt()];
		virusStrain = VirusStrain.values()[in.readInt()];
		quarantineStatus = QuarantineStatus.values()[in.readInt()];
		quarantineDate = in.readInt();
		vaccinationStatus = VaccinationStatus.values()[in.readInt()];
		reVaccinationStatus = VaccinationStatus.values()[in.readInt()];
		vaccinationDate = in.readInt();
		testStatus = TestStatus.values()[in.readInt()];
		testDate = in.readInt();
		traceable = in.readBoolean();
		numInfections = in.readInt();

		// vaccinable, which is not restored from snapshot
		in.readBoolean();

		vaccinationType = VaccinationType.values()[in.readInt()];
		susceptibility = in.readDouble();
	}

	/**
	 * Writes person state to stream.
	 */
	void write(ObjectOutput out) throws IOException {

		out.writeInt(traceableContactPersons.size());
		for (Map.Entry<EpisimPerson, Double> kv : traceableContactPersons.entrySet()) {
			writeChars(out, kv.getKey().getPersonId().toString());
			out.writeDouble(kv.getValue());
		}

		out.writeInt(statusChanges.size());
		for (Map.Entry<DiseaseStatus, Double> e : statusChanges.entrySet()) {
			out.writeInt(e.getKey().ordinal());
			out.writeDouble(e.getValue());
		}

		out.writeBoolean(infectionContainer != null);
		if (infectionContainer != null) {
			writeChars(out, infectionContainer.toString());
		}

		out.writeBoolean(infectionType != null);
		if (infectionType != null) {
			writeChars(out, infectionType);
		}

		out.writeInt(spentTime.size());

		for (Object2DoubleMap.Entry<String> kv : spentTime.object2DoubleEntrySet()) {
			writeChars(out, kv.getKey());
			out.writeDouble(kv.getDoubleValue());
		}

		out.writeInt(status.ordinal());
		out.writeInt(virusStrain.ordinal());
		out.writeInt(quarantineStatus.ordinal());
		out.writeInt(quarantineDate);
		out.writeInt(vaccinationStatus.ordinal());
		out.writeInt(reVaccinationStatus.ordinal());
		out.writeInt(vaccinationDate);
		out.writeInt(testStatus.ordinal());
		out.writeInt(testDate);
		out.writeBoolean(traceable);
		out.writeInt(numInfections);
		out.writeBoolean(vaccinable);
		out.writeInt(vaccinationType.ordinal());
		out.writeDouble(susceptibility);
	}

	public Id<Person> getPersonId() {
		return personId;
	}

	public DiseaseStatus getDiseaseStatus() {
		return status;
	}

	public void setDiseaseStatus(double now, DiseaseStatus status) {
		this.status = status;

		// when person goes back to susceptible, old states are removed
		if (status == DiseaseStatus.susceptible) {
			statusChanges.keySet().removeIf(p -> p != DiseaseStatus.recovered);
		}

		if (!statusChanges.containsKey(status))
			statusChanges.put(status, now);

		reporting.reportPersonStatus(this, new EpisimPersonStatusEvent(now, personId, status));
	}

	/**
	 * Set and report initial infection.
	 */
	public void setInitialInfection(double now, VirusStrain strain) {

		reporting.reportInfection(new EpisimInitialInfectionEvent(now, getPersonId(), strain));

		setVirusStrain(strain);
		setDiseaseStatus(now, EpisimPerson.DiseaseStatus.infectedButNotContagious);

	}

	/**
	 * Adds an infection possibility to this persons. Will be executed in {@link #checkInfection()}
	 */
	synchronized public void possibleInfection(EpisimInfectionEvent event) {
		if (earliestInfection == null || event.compareTo(earliestInfection) < 0) {
			earliestInfection = event;
		}
	}

	/**
	 * Adds a potential infection to the list.
	 */
	synchronized public void potentialInfection(EpisimPotentialInfectionEvent event) {
		potentialInfectionEvents.add(event);
	}

	/**
	 * Update state with a stored {@link EpisimInfectionEvent}.
	 *
	 * @return the event if an infection has occurred.
	 */
	public EpisimInfectionEvent checkInfection() {
		if (earliestInfection != null) {

			EpisimInfectionEvent event = this.earliestInfection;
			setDiseaseStatus(event.getTime(), EpisimPerson.DiseaseStatus.infectedButNotContagious);
			setVirusStrain(event.getVirusStrain());
			infectionContainer = (Id<ActivityFacility>) event.getContainerId();
			setInfectionType(event.getInfectionType());
			numInfections++;

			this.earliestInfection = null;
			return event;
		}

		return null;
	}

	/**
	 * Get all potential infection events.
	 */
	List<EpisimPotentialInfectionEvent> getPotentialInfections() {
		return potentialInfectionEvents;
	}

	public QuarantineStatus getQuarantineStatus() {
		return quarantineStatus;
	}

	public void setQuarantineStatus(QuarantineStatus quarantineStatus, int iteration) {
		this.quarantineStatus = quarantineStatus;
		this.quarantineDate = iteration;

		// this function should receive now instead of iteration
		// only for testing currently
		//reporting.reportPersonStatus(this, new EpisimPersonStatusEvent(iteration * 86400d, personId, quarantineStatus));
	}

	public void setVirusStrain(VirusStrain virusStrain) {
		this.virusStrain = virusStrain;
	}

	public VirusStrain getVirusStrain() {
		return virusStrain;
	}

	public VaccinationStatus getVaccinationStatus() {
		return vaccinationStatus;
	}

	public VaccinationType getVaccinationType() {
		return vaccinationType;
	}

	public VaccinationStatus getReVaccinationStatus() {
		return reVaccinationStatus;
	}

	public void setVaccinationStatus(VaccinationStatus vaccinationStatus, VaccinationType type, int iteration) {
		if (vaccinationStatus != VaccinationStatus.yes) throw new IllegalArgumentException("Vaccination can only be set to yes.");

		this.vaccinationType = type;
		this.vaccinationStatus = vaccinationStatus;
		this.vaccinationDate = iteration;

		reporting.reportVaccination(personId, iteration, type, false);
	}

	public void setReVaccinationStatus(VaccinationStatus vaccinationStatus, int iteration) {
		if (this.vaccinationStatus != VaccinationStatus.yes) throw new IllegalArgumentException("First vaccination must already be present.");
		if (vaccinationStatus != VaccinationStatus.yes) throw new IllegalArgumentException("Re-vaccination can only be set to yes.");

		this.reVaccinationStatus = vaccinationStatus;
		this.vaccinationDate = iteration;

		reporting.reportVaccination(personId, iteration, vaccinationType,true);
	}

	public TestStatus getTestStatus() {
		return testStatus;
	}

	public void setTestStatus(TestStatus testStatus, int iteration) {
		this.testStatus = testStatus;
		this.testDate = iteration;
	}

	public void setSusceptibility(double susceptibility) {
		this.susceptibility = susceptibility;
	}

	public double getSusceptibility() {
		return susceptibility;
	}

	/**
	 * Days elapsed since a certain status was set.
	 * This will always round the change as if it happened on the start of a day.
	 *
	 * @param status     requested status
	 * @param currentDay current day (iteration)
	 * @throws IllegalStateException when the requested status was never set
	 */
	public int daysSince(DiseaseStatus status, int currentDay) {
		if (!statusChanges.containsKey(status)) throw new IllegalStateException("Person was never " + status);

		double day = Math.floor(statusChanges.get(status) / EpisimUtils.DAY);

		return currentDay - (int) day;
	}

	/**
	 * Return whether a person had (or currently has) a certain disease status.
	 */
	public boolean hadDiseaseStatus(DiseaseStatus status) {
		return statusChanges.containsKey(status);
	}

	/**
	 * Days elapsed since person was put into quarantine.
	 *
	 * @param currentDay current day (iteration)
	 * @apiNote This is currently not used much and may change similar to {@link #daysSince(DiseaseStatus, int)}.
	 */
	@Beta
	public int daysSinceQuarantine(int currentDay) {

		// yyyy since this API is so unstable, I would prefer to have the class non-public.  kai, apr'20
		// -> api now marked as unstable and containing an api note, because it is used by the models it has to be public. chr, apr'20
		if (quarantineDate < 0) throw new IllegalStateException("Person was never quarantined");

		return currentDay - quarantineDate;
	}

	/**
	 * Days elapsed since person got its first vaccination.
	 *
	 * @param currentDay current day (iteration)
	 */
	public int daysSince(VaccinationStatus status, int currentDay) {
		if (status != VaccinationStatus.yes) throw new IllegalArgumentException("Only supports querying when person was vaccinated");
		if (vaccinationDate < 0) throw new IllegalStateException("Person was never vaccinated");

		return currentDay - vaccinationDate;
	}

	/**
	 * Days elapsed since person got its first vaccination.
	 *
	 * @param currentDay current day (iteration)
	 */
	public int daysSinceTest(int currentDay) {
		if (testDate < 0)
			return Integer.MAX_VALUE;

		return currentDay - testDate;
	}

	/**
	 * Number of times person was infected.
	 */
	public int getNumInfections() {
		return numInfections;
	}

	/**
	 * Whether this person is handled as a recovered person.
	 */
	public boolean isRecentlyRecovered(int currentDay) {
		return status == DiseaseStatus.recovered || (status == DiseaseStatus.susceptible && numInfections >= 1 && daysSince(DiseaseStatus.recovered, currentDay) <= 180);
	}

	public synchronized void addTraceableContactPerson(EpisimPerson personWrapper, double now) {
		// check if both persons have tracing capability
		if (isTraceable() && personWrapper.isTraceable()) {
			// Always use the latest tracking date
			traceableContactPersons.put(personWrapper, now);
			reporting.reportTracing(now, this, personWrapper);
		}
	}

	/**
	 * Get all traced contacts that happened after certain time.
	 */
	public synchronized List<EpisimPerson> getTraceableContactPersons(double after) {
		// needs to be sorted or results will be non deterministic with multithreading
		return traceableContactPersons.object2DoubleEntrySet()
				.stream().filter(p -> p.getDoubleValue() >= after)
				.map(Map.Entry::getKey)
				.sorted(Comparator.comparing(EpisimPerson::getPersonId))
				.collect(Collectors.toList());

		// yyyy if the computationally intensive operation is to search by time, we should sort traceableContactPersons by time.  To simplify this, I
		// would argue that it is not a problem to have a person in there multiple times.  kai, may'20

	}

	/**
	 * Remove old contact tracing data before a certain date.
	 */
	public void clearTraceableContractPersons(double before) {

		int oldSize = traceableContactPersons.size();

		if (oldSize == 0) return;

		traceableContactPersons.keySet().removeIf(k -> traceableContactPersons.getDouble(k) < before);
	}

	/**
	 * Returns whether the person can be traced.
	 */
	public boolean isTraceable() {
		return traceable;
	}

	void setTraceable(boolean traceable) {
		this.traceable = traceable;
	}

	public boolean isVaccinable() {
		return vaccinable;
	}

	/**
	 * Set vaccinable status.
	 */
	public void setVaccinable(boolean vaccinable) {
		this.vaccinable = vaccinable;
	}

	public PerformedActivity addToTrajectory(double time, EpisimConfigGroup.InfectionParams trajectoryElement, Id<ActivityFacility> facilityId) {
		PerformedActivity act = new PerformedActivity(time, trajectoryElement, facilityId);
		trajectory.add(act);
		return act;
	}


	void setStartOfDay(DayOfWeek day) {
		startOfDay[day.getValue() - 1] = trajectory.size();
	}

	int getStartOfDay(DayOfWeek day) {
		return startOfDay[day.getValue() - 1];
	}

	void setEndOfDay(DayOfWeek day) {
		endOfDay[day.getValue() - 1] = trajectory.size();
	}

	int getEndOfDay(DayOfWeek day) {
		return endOfDay[day.getValue() - 1];
	}

	/**
	 * Matches all activities of a person for a day. Calls {@code reduce} on all matched activities.
	 * This method takes {@link #activityParticipation} into account.
	 *
	 * @param reduce       reduce function called on each activities with current result
	 * @param defaultValue default value and initial value for the reduce function
	 */
	public <T> T matchActivities(DayOfWeek day, Set<String> activities, BiFunction<String, T, T> reduce, T defaultValue) {

		T result = defaultValue;
		for (int i = getStartOfDay(day); i < getEndOfDay(day); i++) {
			String act = trajectory.get(i).params.getContainerName();
			if (activityParticipation.get(i) && activities.contains(act))
				result = reduce.apply(act, result);
		}

		return result;

	}

	/**
	 * Whether this person has any activity for given day.
	 * Used during initialization. After that it should always return true.
	 */
	boolean hasActivity(DayOfWeek day) {
		return getStartOfDay(day) < trajectory.size();
	}

	/**
	 * Init participation bit set.
	 */
	void initParticipation() {
		activityParticipation = new BitSet(trajectory.size());
		activityParticipation.set(0, trajectory.size(), true);
	}

	public BitSet getActivityParticipation() {
		return activityParticipation;
	}

	/**
	 * Defines that day {@code target} has the same trajectory as {@code source}.
	 */
	void duplicateDay(DayOfWeek target, DayOfWeek source) {
		startOfDay[target.getValue() - 1] = startOfDay[source.getValue() - 1];
		endOfDay[target.getValue() - 1] = endOfDay[source.getValue() - 1];
		firstFacilityId[target.getValue() - 1] = firstFacilityId[source.getValue() - 1];
		lastFacilityId[target.getValue() - 1] = lastFacilityId[source.getValue() - 1];
		staysInContainer[target.getValue() - 1] = staysInContainer[source.getValue() - 1];
	}

	/**
	 * Reset all trajectory information
	 */
	void resetTrajectory() {
		trajectory.clear();
		Arrays.fill(startOfDay, 0);
		Arrays.fill(endOfDay, 0);
		Arrays.fill(firstFacilityId, null);
		Arrays.fill(lastFacilityId, null);
		Arrays.fill(staysInContainer, false);
	}

	@Override
	public Attributes getAttributes() {
		return attributes;
	}

	public int getAge() {
		assert age != -1 : "Person=" + getPersonId().toString() + " has no age.";
		assert age >= 0 && age <= 120 : "Age of person=" + getPersonId().toString() + " is not plausible. Age is=" + age;

		return age;
	}

	/**
	 * Return the age of a person or the default age if no age is specified.
	 */
	public int getAgeOrDefault(int defaultAge) {
		return age != -1 ? age : defaultAge;
	}

	Id<ActivityFacility> getFirstFacilityId(DayOfWeek day) {
		return firstFacilityId[day.getValue() - 1];
	}

	void setFirstFacilityId(Id<ActivityFacility> firstFacilityId, DayOfWeek day) {
		this.firstFacilityId[day.getValue() - 1] = firstFacilityId;
	}

	Id<ActivityFacility> getLastFacilityId(DayOfWeek day) {
		return lastFacilityId[day.getValue() - 1];
	}

	void setLastFacilityId(Id<ActivityFacility> lastFacilityId, DayOfWeek day, boolean stays) {
		this.lastFacilityId[day.getValue() - 1] = lastFacilityId;
		this.staysInContainer[day.getValue() - 1] = stays;
	}

	void setStaysInContainer(DayOfWeek day, boolean stays) {
		this.staysInContainer[day.getValue() - 1] = stays;
	}

	boolean getStaysInContainer(DayOfWeek day) {
		return staysInContainer[day.getValue() - 1];
	}

	public Id<ActivityFacility> getInfectionContainer() {
		return infectionContainer;
	}

	public void setInfectionType(String infectionType) {
		this.infectionType = infectionType;
	}

	public String getInfectionType() {
		return infectionType;
	}

	/**
	 * Add amount of time to spent time for an activity.
	 */
	public synchronized void addSpentTime(String actType, double timeSpent) {
		spentTime.mergeDouble(actType, timeSpent, Double::sum);
	}

	/**
	 * Spent time of this person by activity.
	 */
	public Object2DoubleMap<String> getSpentTime() {
		return spentTime;
	}

	@Override
	public String toString() {
		return "EpisimPerson{" +
				"personId=" + personId +
				'}';
	}

	private int findActivity(DayOfWeek day, double time) {
		// do a linear search for matching activity
		int last = getEndOfDay(day) - 1;
		for (int i = getStartOfDay(day); i < last; i++) {
			if (trajectory.get(i + 1).time > time)
				return i;
		}
		return last;
	}

	private int findFirstActivity(DayOfWeek day, double time) {
		int last = getEndOfDay(day) - 1;
		for (int i = getStartOfDay(day); i < last; i++) {
			if (trajectory.get(i + 1).time >= time)
				return i;
		}
		return last;
	}

	/**
	 * Checks whether a certain activity is performed.
	 */
	boolean checkActivity(DayOfWeek day, double time) {
		return activityParticipation.get(findActivity(day, time));
	}

	boolean checkFirstActivity(DayOfWeek day, double time) {
		return activityParticipation.get(findFirstActivity(day, time));
	}

	/**
	 * Checks whether the next activity is performed.
	 */
	boolean checkNextActivity(DayOfWeek day, double time) {
		int idx = findActivity(day, time);

		if (idx < getEndOfDay(day) - 1)
			return activityParticipation.get(idx + 1);

		return true;
	}

	public List<PerformedActivity> getActivities(DayOfWeek day) {
		int offset = getStartOfDay(day);
		return trajectory.subList(offset, getEndOfDay(day));
	}


	/**
	 * Return the first activity of a person for specific day.
	 */
	PerformedActivity getFirstActivity(DayOfWeek day) {
		return trajectory.get(getStartOfDay(day));
	}

	PerformedActivity getLastActivity(DayOfWeek day) {
		return trajectory.get(getEndOfDay(day) - 1);
	}

	/**
	 * Get the activity normally performed by a person on a specific day and time.
	 */
	public PerformedActivity getActivity(DayOfWeek day, double time) {

		assert getStartOfDay(day) >= 0;
		assert getEndOfDay(day) <= trajectory.size();

		return trajectory.get(findActivity(day, time));
	}

	/**
	 * Get the next activity of a person.
	 *
	 * @see #getActivity(DayOfWeek, double)
	 */
	@Nullable
	public PerformedActivity getNextActivity(DayOfWeek day, double time) {
		int idx = findActivity(day, time);

		if (idx < getEndOfDay(day) - 1)
			return trajectory.get(idx + 1);

		return null;
	}

	/**
	 * Disease status of a person.
	 */
	public enum DiseaseStatus {
		susceptible, infectedButNotContagious, contagious, showingSymptoms,
		seriouslySick, critical, seriouslySickAfterCritical, recovered
	}

	/**
	 * Quarantine status of a person.
	 */
	public enum QuarantineStatus {full, atHome, no}

	/**
	 * Latest test result of this person.
	 */
	public enum TestStatus {untested, positive, negative}

	/**
	 * Status of vaccination.
	 */
	public enum VaccinationStatus {yes, no}

	/**
	 * Stores when an activity is performed and in which context.
	 */
	public static final class PerformedActivity {

		public final double time;
		public final EpisimConfigGroup.InfectionParams params;
		public final Id<ActivityFacility> facilityId;


		public PerformedActivity(double time, EpisimConfigGroup.InfectionParams params, Id<ActivityFacility> facilityId) {
			this.time = time;
			this.params = params;
			this.facilityId = facilityId;
		}

		/**
		 * Starting time of an activity.
		 */
		public double time() {
			return time;
		}

		/**
		 * Activity type as string.
		 */
		public String actType() {
			// container name is quite misleading and not the correct anymore.
			return params.getContainerName();
		}

		/**
		 * Facility Id for performed activity
		 */
		public Id<ActivityFacility> getFacilityId() {
			return this.facilityId;
		}


		@Override
		public String toString() {
			return "PerformedActivity{" +
					"time=" + time +
					", params=" + params +
					'}';
		}
	}

	/**
	 * Not further specified activity that is used during initialization.
	 */
	static final PerformedActivity UNSPECIFIC_ACTIVITY = new PerformedActivity(Double.NaN, null, null);

    /**
	 * If the ContagiousOptimization is enabled, containers count how many
	 * persons satisfy this predicate to call the infectionsDynamics methods
     * only in the case that at least one person in the container
	 * can infect another (or in the infectedButNotContagious case,
	 * inform other persons later thanks to tracking).
	 */
	public boolean infectedButNotSerious() {
		return (status == DiseaseStatus.infectedButNotContagious ||
				status == DiseaseStatus.contagious ||
				status == DiseaseStatus.showingSymptoms);
	}
}

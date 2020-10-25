package org.matsim.episim;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.episim.data.*;
import org.matsim.facilities.ActivityFacility;
import org.matsim.utils.objectattributes.attributable.Attributes;
import org.mockito.Mockito;

import javax.annotation.Nullable;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class EpisimTestUtils {

	public static final Consumer<MutableEpisimPerson> CONTAGIOUS = person -> person.setDiseaseStatus(0., DiseaseStatus.contagious);
	public static final Consumer<MutableEpisimPerson> SYMPTOMS = person -> person.setDiseaseStatus(0., DiseaseStatus.showingSymptoms);

	public static final Consumer<MutableEpisimPerson> FULL_QUARANTINE = person -> {
		person.setDiseaseStatus(0, DiseaseStatus.contagious);
		person.setQuarantineStatus(QuarantineStatus.full, 0);
	};

	public static final Consumer<MutableEpisimPerson> HOME_QUARANTINE = person -> {
		person.setDiseaseStatus(0, DiseaseStatus.showingSymptoms);
		person.setQuarantineStatus(QuarantineStatus.atHome, 0);
	};

	private static final AtomicLong ID = new AtomicLong(0);
	private static final EpisimReporting reporting = Mockito.mock(EpisimReporting.class, Mockito.withSettings().stubOnly());
	private static Map<Id<Person>, MutableEpisimPerson> persons = new IdentityHashMap<>();

	public static final EpisimConfigGroup TEST_CONFIG = ConfigUtils.addOrGetModule(createTestConfig(), EpisimConfigGroup.class);

	/**
	 * Reset the person id counter.
	 */
	public static void resetIds() {
		ID.set(0);
	}

	/**
	 * Creates test config with some default interactions.
	 *
	 * @return
	 */
	public static Config createTestConfig() {
		Config config = ConfigUtils.createConfig(new EpisimConfigGroup());
		EpisimConfigGroup episimConfig = ConfigUtils.addOrGetModule(config, EpisimConfigGroup.class);

		episimConfig.setSampleSize(1);
		episimConfig.setMaxContacts(10);
		episimConfig.setCalibrationParameter(0.001);

		// No container name should be the prefix of another one
		episimConfig.addContainerParams(new EpisimConfigGroup.InfectionParams("c00").setContactIntensity(0));
		episimConfig.addContainerParams(new EpisimConfigGroup.InfectionParams("c0.1").setContactIntensity(0.1));
		episimConfig.addContainerParams(new EpisimConfigGroup.InfectionParams("c0.5").setContactIntensity(0.5));
		episimConfig.addContainerParams(new EpisimConfigGroup.InfectionParams("c1.0").setContactIntensity(1));
		episimConfig.addContainerParams(new EpisimConfigGroup.InfectionParams("c5").setContactIntensity(5));
		episimConfig.addContainerParams(new EpisimConfigGroup.InfectionParams("c10").setContactIntensity(10));
		episimConfig.addContainerParams(new EpisimConfigGroup.InfectionParams("home").setContactIntensity(1));
		episimConfig.addContainerParams(new EpisimConfigGroup.InfectionParams("quarantine_home").setContactIntensity(1));
		episimConfig.addContainerParams(new EpisimConfigGroup.InfectionParams("leis").setContactIntensity(1));
		episimConfig.addContainerParams(new EpisimConfigGroup.InfectionParams("work").setContactIntensity(1));
		episimConfig.addContainerParams(new EpisimConfigGroup.InfectionParams("edu").setContactIntensity(1));
		episimConfig.addContainerParams(new EpisimConfigGroup.InfectionParams("tr").setContactIntensity(1));

		return config;
	}

	public static MutableEpisimContainer createFacility() {
		return new MutableEpisimContainer(Id.create(ID.getAndIncrement(), ActivityFacility.class), false);
	}

	/**
	 * Create facility with n persons in it.
	 */
	public static MutableEpisimContainer createFacility(int n, String act, Consumer<MutableEpisimPerson> init) {
		MutableEpisimContainer container = createFacility();
		return addPersons(container, n, act, init);
	}

	/**
	 * Create a facility with certain group size.
	 */
	public static MutableEpisimContainer createFacility(int n, String act, int groupSize, Consumer<MutableEpisimPerson> init) {
		MutableEpisimContainer container = createFacility();
		container.setMaxGroupSize(groupSize);
		return addPersons(container, n, act, init);
	}

	/**
	 * Create a person and add to container.
	 */
	public static MutableEpisimPerson createPerson(String currentAct, @Nullable MutableEpisimContainer container) {
		MutableEpisimPerson p = new MutableEpisimPerson(Id.createPersonId(ID.getAndIncrement()), new Attributes(), reporting);
		persons.put(p.getPersonId(), p);

		p.getTrajectory().add(new MutableEpisimPerson.Activity(currentAct, TEST_CONFIG.selectInfectionParams(currentAct)));

		if (container != null) {
			container.addPerson(p, 0);
		}

		return p;
	}

	/**
	 * Create a person with specific reporting.
	 */
	public static MutableEpisimPerson createPerson(EpisimReporting reporting) {
		MutableEpisimPerson p = new MutableEpisimPerson(Id.createPersonId(ID.getAndIncrement()), new Attributes(), reporting);
		persons.put(p.getPersonId(), p);
		return p;
	}

	/**
	 * Add persons to a facility.
	 */
	public static MutableEpisimContainer addPersons(MutableEpisimContainer container, int n,
													String act, Consumer<MutableEpisimPerson> init) {
		for (int i = 0; i < n; i++) {
			MutableEpisimPerson p = createPerson(act, container);
			persons.put(p.getPersonId(), p);
			init.accept(p);
		}

		return container;
	}

	/**
	 * Remove person from container.
	 */
	public static void removePerson(MutableEpisimContainer container, MutableEpisimPerson p) {
		container.removePerson(p);
	}


	/**
	 * Report with zero values.
	 */
	public static EpisimReporting.InfectionReport createReport(String date, long day) {
		return new EpisimReporting.InfectionReport("test", 0, date, day);
	}

	/**
	 * Return map of all persons.
	 */
	public static Map<Id<Person>, MutableEpisimPerson> createPersons() {
		persons = new ConcurrentHashMap<>();
		return persons;
	}

	/**
	 * Create a mutable leave event with given context.
	 */
	public static PersonLeavesContainerEvent createEvent(int now, MutableEpisimPerson person, MutableEpisimContainer container, EpisimConfigGroup.InfectionParams actType) {
		MutablePersonLeavesContainerEvent e = new MutablePersonLeavesContainerEvent();
		e.setContext(now, person, container, actType);
		return e;
	}

}

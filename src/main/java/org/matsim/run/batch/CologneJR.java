package org.matsim.run.batch;

import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.Singleton;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.util.Modules;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.episim.*;
import org.matsim.episim.EpisimConfigGroup.SnapshotSeed;
import org.matsim.episim.EpisimPerson.QuarantineStatus;
import org.matsim.episim.analysis.OutputAnalysis;
import org.matsim.episim.analysis.RValuesFromEvents;
import org.matsim.episim.analysis.VaccinationEffectiveness;
import org.matsim.episim.analysis.VaccinationEffectivenessFromPotentialInfections;
import org.matsim.episim.model.*;
import org.matsim.episim.model.testing.TestType;
import org.matsim.episim.model.vaccination.VaccinationModel;
import org.matsim.episim.model.vaccination.VaccinationStrategy;
import org.matsim.episim.policy.FixedPolicy;
import org.matsim.episim.policy.FixedPolicy.ConfigBuilder;
import org.matsim.episim.policy.Restriction;
import org.matsim.run.RunParallel;
import org.matsim.run.modules.SnzCologneProductionScenario;

import javax.annotation.Nullable;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;


/**
 * Batch for Bmbf runs
 */
public class CologneJR implements BatchRun<CologneJR.Params> {

	boolean DEBUG_MODE = false;

	@Nullable
	@Override
	public Module getBindings(int id, @Nullable Params params) {
		return Modules.override(getBindings(0.0)).with(new AbstractModule() {
			@Override
			protected void configure() {

				Multibinder<VaccinationModel> set = Multibinder.newSetBinder(binder(), VaccinationModel.class);

				set.addBinding().to(VaccinationStrategy.class).in(Singleton.class);
				LocalDate oVacStartDate = null;
				int campaignDuration = 0;

				AntibodyModel.Config antibodyModelConfig = new AntibodyModel.Config();

				if (params != null) {
					oVacStartDate = LocalDate.parse("2099-01-01");
					campaignDuration = 50;

					Map<EpisimPerson.ImmuneResponse, Double> immuneResponseDoubleMap = new HashMap<>();
					immuneResponseDoubleMap.put(EpisimPerson.ImmuneResponse.low, params.immuneRespLow);
					immuneResponseDoubleMap.put(EpisimPerson.ImmuneResponse.normal, 1.);
					immuneResponseDoubleMap.put(EpisimPerson.ImmuneResponse.high, params.immuneRespHigh);
					antibodyModelConfig.setImmuneResponseMultiplier(immuneResponseDoubleMap);
				}

				bind(VaccinationStrategy.Config.class).toInstance(new VaccinationStrategy.Config(oVacStartDate, campaignDuration, VaccinationType.mRNA, 0., 0.));
				bind(AntibodyModel.Config.class).toInstance(antibodyModelConfig);

			}
		});
	}

	private SnzCologneProductionScenario getBindings(double pHousehold) {
		return new SnzCologneProductionScenario.Builder()
				.setScaleForActivityLevels(1.3)
				.setSuscHouseholds_pct(pHousehold)
				.setActivityHandling(EpisimConfigGroup.ActivityHandling.startOfDay)
				.setInfectionModel(InfectionModelWithAntibodies.class)
				.build();
	}

	@Override
	public Metadata getMetadata() {
		return Metadata.of("cologne", "calibration");
	}

	@Override
	public Collection<OutputAnalysis> postProcessing() {
		return List.of(
				new VaccinationEffectiveness().withArgs(),
				new RValuesFromEvents().withArgs(),
				new VaccinationEffectivenessFromPotentialInfections().withArgs("--remove-infected")
		);
	}

	@Override
	public Config prepareConfig(int id, Params params) {

		if (DEBUG_MODE) {
			if (params.seed == 4711 && params.immuneShare == 0.3 && params.immuneDate.equals("2021-11-15") && params.ba1ba2x.equals("true") && params.ba1Inf == 2.1 && params.ba2Inf == 0.0) {
			} else {
				return null;
			}
		}



		LocalDate restrictionDate = LocalDate.parse("2022-03-01");

		SnzCologneProductionScenario module = getBindings(0.0);

		Config config = module.config();


		config.global().setRandomSeed(params.seed);

		EpisimConfigGroup episimConfig = ConfigUtils.addOrGetModule(config, EpisimConfigGroup.class);

		double tf = 1.0;
		episimConfig.setCalibrationParameter(episimConfig.getCalibrationParameter() * 0.96 * 1.06 * tf);

		episimConfig.setImmuneShare(params.immuneShare);
		episimConfig.setImmuneDate(DEBUG_MODE ? "2020-03-15" : params.immuneDate);


		//local (see svn for more snapshots with different dates)
//		episimConfig.setStartFromSnapshot("../shared-svn/projects/episim/matsim-files/snz/Cologne/episim-input/snapshots-cologne-20220218/" + params.seed + "-540-2021-08-17.zip");

		//cluster
		if (!DEBUG_MODE) {
			episimConfig.setStartFromSnapshot("/scratch/projects/bzz0020/episim-input/snapshots-cologne-20220316/" + params.seed + "-450-2021-05-19.zip");
			episimConfig.setSnapshotSeed(SnapshotSeed.restore);

		}

		//restrictions
		ConfigBuilder builder = FixedPolicy.parse(episimConfig.getPolicy());


		double leis = 1.0;
		builder.restrict(LocalDate.parse("2021-12-01"), Restriction.ofVaccinatedRf(0.75), "leisure");
		builder.restrict(restrictionDate, Restriction.ofVaccinatedRf(leis), "leisure");

		//2G
		builder.restrict(LocalDate.parse("2021-11-22"), Restriction.ofSusceptibleRf(0.75), "leisure");
		builder.restrict(restrictionDate, Restriction.ofSusceptibleRf(leis), "leisure");

		double schoolFac = 0.5;
		builder.restrict(LocalDate.parse("2021-08-17"), Restriction.ofCiCorrection(1 - (0.5 * schoolFac)), "educ_primary", "educ_kiga", "educ_secondary", "educ_tertiary", "educ_other");

		builder.restrict(LocalDate.parse("2021-08-17"), Restriction.ofMask(FaceMask.N95, 0.9 * schoolFac), "educ_primary", "educ_secondary", "educ_higher", "educ_tertiary", "educ_other");
		builder.restrict(LocalDate.parse("2021-11-02"), Restriction.ofMask(FaceMask.N95, 0.0), "educ_primary", "educ_secondary", "educ_tertiary", "educ_other");
		builder.restrict(LocalDate.parse("2021-12-02"), Restriction.ofMask(FaceMask.N95, 0.9 * schoolFac), "educ_primary", "educ_secondary", "educ_tertiary", "educ_other");

		//		builder.restrict(restrictionDate, 0.78, "work", "leisure", "shop_daily", "shop_other", "visit", "errands", "business");

		episimConfig.setPolicy(builder.build());

		Map<LocalDate, DayOfWeek> inputDays = new HashMap<>();
		inputDays.put(LocalDate.parse("2021-11-01"), DayOfWeek.SUNDAY);

		episimConfig.setInputDays(inputDays);

		//mutations
		VirusStrainConfigGroup virusStrainConfigGroup = ConfigUtils.addOrGetModule(config, VirusStrainConfigGroup.class);

		Map<LocalDate, Integer> infPerDayB117 = new HashMap<>();
		infPerDayB117.put(LocalDate.parse("2020-01-01"), 0);

		infPerDayB117.put(LocalDate.parse("2021-01-16"), 20);
		infPerDayB117.put(LocalDate.parse("2021-01-16").plusDays(1), 1);
		infPerDayB117.put(LocalDate.parse("2020-12-31"), 1);

		episimConfig.setInfections_pers_per_day(VirusStrain.ALPHA, infPerDayB117);

		virusStrainConfigGroup.getOrAddParams(VirusStrain.ALPHA).setInfectiousness(1.7);
		virusStrainConfigGroup.getOrAddParams(VirusStrain.ALPHA).setFactorSeriouslySick(1.0);

		Map<LocalDate, Integer> infPerDayMUTB = new HashMap<>();
		infPerDayMUTB.put(LocalDate.parse("2020-01-01"), 0);
		infPerDayMUTB.put(LocalDate.parse("2021-06-21"), 10);
		infPerDayMUTB.put(LocalDate.parse("2021-06-21").plusDays(1), 1);


		//disease import 2021
		double cologneFactor = 0.5;
		int imp1 = 10;
		SnzCologneProductionScenario.interpolateImport(infPerDayMUTB, cologneFactor * 5, LocalDate.parse("2021-07-03").plusDays(0),
				LocalDate.parse("2021-07-25").plusDays(0), 1, 48);
		SnzCologneProductionScenario.interpolateImport(infPerDayMUTB, cologneFactor * 5, LocalDate.parse("2021-07-26").plusDays(0),
				LocalDate.parse("2021-08-17").plusDays(0), 48, imp1);

		int imp = (int) (48 * 0.5);
		imp = Math.max(imp, imp1);

		SnzCologneProductionScenario.interpolateImport(infPerDayMUTB, cologneFactor * 5, LocalDate.parse("2021-10-09").plusDays(0),
				LocalDate.parse("2021-10-16").plusDays(0), imp1, imp);
		SnzCologneProductionScenario.interpolateImport(infPerDayMUTB, cologneFactor * 5, LocalDate.parse("2021-10-17").plusDays(0),
				LocalDate.parse("2021-10-24").plusDays(0), imp, imp1);
		infPerDayMUTB.put(LocalDate.parse("2021-10-25"), 1);
		;
		episimConfig.setInfections_pers_per_day(VirusStrain.DELTA, infPerDayMUTB);
		double deltaInf = 2.7;
		double deltaHos = 1.5;
		virusStrainConfigGroup.getOrAddParams(VirusStrain.DELTA).setInfectiousness(deltaInf);
		virusStrainConfigGroup.getOrAddParams(VirusStrain.DELTA).setFactorSeriouslySick(deltaHos);
		virusStrainConfigGroup.getOrAddParams(VirusStrain.DELTA).setFactorSeriouslySickVaccinated(deltaHos);


		//omicron
		double oInf = params.ba1Inf;
		double oHos = 0.3;
		if (params.ba1Inf > 0) {
			Map<LocalDate, Integer> infPerDayOmicron = new HashMap<>();
			infPerDayOmicron.put(LocalDate.parse("2020-01-01"), 0);
			infPerDayOmicron.put(LocalDate.parse(params.ba1Date), 4);
			infPerDayOmicron.put(LocalDate.parse(params.ba1Date).plusDays(6), 1);
			episimConfig.setInfections_pers_per_day(VirusStrain.OMICRON_BA1, infPerDayOmicron);
			virusStrainConfigGroup.getOrAddParams(VirusStrain.OMICRON_BA1).setInfectiousness(deltaInf * oInf);
			virusStrainConfigGroup.getOrAddParams(VirusStrain.OMICRON_BA1).setFactorSeriouslySick(oHos);
			virusStrainConfigGroup.getOrAddParams(VirusStrain.OMICRON_BA1).setFactorSeriouslySickVaccinated(oHos);
			virusStrainConfigGroup.getOrAddParams(VirusStrain.OMICRON_BA1).setFactorCritical(oHos);
		}


		//BA.2
		if (params.ba2Inf > 0) {
			Map<LocalDate, Integer> infPerDayBA2 = new HashMap<>();
			infPerDayBA2.put(LocalDate.parse("2020-01-01"), 0);
			infPerDayBA2.put(LocalDate.parse(params.ba2Date), 4);
			infPerDayBA2.put(LocalDate.parse(params.ba2Date).plusDays(6), 1);
			episimConfig.setInfections_pers_per_day(VirusStrain.OMICRON_BA2, infPerDayBA2);
			virusStrainConfigGroup.getOrAddParams(VirusStrain.OMICRON_BA2).setInfectiousness(deltaInf * oInf * params.ba2Inf);
			virusStrainConfigGroup.getOrAddParams(VirusStrain.OMICRON_BA2).setFactorSeriouslySick(oHos);
			virusStrainConfigGroup.getOrAddParams(VirusStrain.OMICRON_BA2).setFactorSeriouslySickVaccinated(oHos);
			virusStrainConfigGroup.getOrAddParams(VirusStrain.OMICRON_BA2).setFactorCritical(oHos);
		}


		//vaccinations
		VaccinationConfigGroup vaccinationConfig = ConfigUtils.addOrGetModule(config, VaccinationConfigGroup.class);
		vaccinationConfig.setUseIgA(Boolean.valueOf(params.ba1ba2x));
		Map<Integer, Double> vaccinationCompliance = new HashMap<>();
		for (int i = 0; i < 5; i++) vaccinationCompliance.put(i, 0.0);
		for (int i = 5; i <= 11; i++) vaccinationCompliance.put(i, 0.4);
		for (int i = 12; i <= 120; i++) vaccinationCompliance.put(i, 1.0);
		vaccinationConfig.setCompliancePerAge(vaccinationCompliance);

		Map<LocalDate, Integer> vaccinations = new HashMap<>();
		double population = 2_352_480;
		vaccinations.put(LocalDate.parse("2022-02-15"), (int) (0.0035 * population / 7));
		vaccinations.put(LocalDate.parse("2022-06-30"), 0);

		vaccinationConfig.setVaccinationCapacity_pers_per_day(vaccinations);
		vaccinationConfig.setDaysValid(270);
		vaccinationConfig.setValidDeadline(LocalDate.parse("2022-01-01"));

		vaccinationConfig.setBeta(1.2);

		configureBooster(vaccinationConfig, 1.0, 3);

		//testing
		TestingConfigGroup testingConfigGroup = ConfigUtils.addOrGetModule(config, TestingConfigGroup.class);

		testingConfigGroup.setTestAllPersonsAfter(LocalDate.parse("2021-10-01"));

		TestingConfigGroup.TestingParams rapidTest = testingConfigGroup.getOrAddParams(TestType.RAPID_TEST);
		TestingConfigGroup.TestingParams pcrTest = testingConfigGroup.getOrAddParams(TestType.PCR);

		testingConfigGroup.setStrategy(TestingConfigGroup.Strategy.ACTIVITIES);

		List<String> actsList = new ArrayList<String>();
		actsList.add("leisure");
		actsList.add("work");
		actsList.add("business");
		actsList.add("educ_kiga");
		actsList.add("educ_primary");
		actsList.add("educ_secondary");
		actsList.add("educ_tertiary");
		actsList.add("educ_other");
		actsList.add("educ_higher");
		testingConfigGroup.setActivities(actsList);

		rapidTest.setFalseNegativeRate(0.3);
		rapidTest.setFalsePositiveRate(0.03);

		pcrTest.setFalseNegativeRate(0.1);
		pcrTest.setFalsePositiveRate(0.01);

		testingConfigGroup.setHouseholdCompliance(1.0);

		LocalDate testingStartDate = LocalDate.parse("2021-03-19");

		Map<LocalDate, Double> leisureTests = new HashMap<LocalDate, Double>();
		Map<LocalDate, Double> workTests = new HashMap<LocalDate, Double>();
		Map<LocalDate, Double> eduTests = new HashMap<LocalDate, Double>();
		Map<LocalDate, Double> kigaPrimaryTests = new HashMap<LocalDate, Double>();
		Map<LocalDate, Double> uniTests = new HashMap<LocalDate, Double>();
		leisureTests.put(LocalDate.parse("2020-01-01"), 0.);
		workTests.put(LocalDate.parse("2020-01-01"), 0.);
		eduTests.put(LocalDate.parse("2020-01-01"), 0.);
		kigaPrimaryTests.put(LocalDate.parse("2020-01-01"), 0.);
		uniTests.put(LocalDate.parse("2020-01-01"), 0.);

		for (int i = 1; i <= 31; i++) {
			leisureTests.put(testingStartDate.plusDays(i), 0.1 * i / 31.);
			workTests.put(testingStartDate.plusDays(i), 0.1 * i / 31.);
			eduTests.put(testingStartDate.plusDays(i), 0.4 * i / 31.);
			kigaPrimaryTests.put(testingStartDate.plusDays(i), 0.4 * i / 31.);
			uniTests.put(testingStartDate.plusDays(i), 0.8 * i / 31.);

		}

		kigaPrimaryTests.put(LocalDate.parse("2021-05-10"), 0.0);

		workTests.put(LocalDate.parse("2021-06-04"), 0.05);

		workTests.put(LocalDate.parse("2021-11-24"), 0.5);

		leisureTests.put(LocalDate.parse("2021-06-04"), 0.05);
		leisureTests.put(LocalDate.parse("2021-08-23"), 0.2);

		eduTests.put(LocalDate.parse("2021-09-20"), 0.6);

		if (params.testing.equals("no")) {
			kigaPrimaryTests.put(restrictionDate, 0.0);
			workTests.put(restrictionDate, 0.0);
			leisureTests.put(restrictionDate, 0.0);
			eduTests.put(restrictionDate, 0.0);
			uniTests.put(restrictionDate, 0.0);
		}

		rapidTest.setTestingRatePerActivityAndDate((Map.of(
				"leisure", leisureTests,
				"work", workTests,
				"business", workTests,
				"educ_kiga", eduTests,
				"educ_primary", eduTests,
				"educ_secondary", eduTests,
				"educ_tertiary", eduTests,
				"educ_higher", uniTests,
				"educ_other", eduTests
		)));

		Map<LocalDate, Double> leisureTestsVaccinated = new HashMap<LocalDate, Double>();
		Map<LocalDate, Double> workTestsVaccinated = new HashMap<LocalDate, Double>();
		Map<LocalDate, Double> eduTestsVaccinated = new HashMap<LocalDate, Double>();

		leisureTestsVaccinated.put(LocalDate.parse("2020-01-01"), 0.);
		workTestsVaccinated.put(LocalDate.parse("2020-01-01"), 0.);
		eduTestsVaccinated.put(LocalDate.parse("2020-01-01"), 0.);

		leisureTestsVaccinated.put(LocalDate.parse("2021-08-23"), 0.2);

		if (params.testing.equals("no")) {
			leisureTestsVaccinated.put(restrictionDate, 0.0);
			workTestsVaccinated.put(restrictionDate, 0.0);
			eduTestsVaccinated.put(restrictionDate, 0.0);
		}


		rapidTest.setTestingRatePerActivityAndDateVaccinated((Map.of(
				"leisure", leisureTestsVaccinated,
				"work", workTestsVaccinated,
				"business", workTestsVaccinated,
				"educ_kiga", eduTestsVaccinated,
				"educ_primary", eduTestsVaccinated,
				"educ_secondary", eduTestsVaccinated,
				"educ_tertiary", eduTestsVaccinated,
				"educ_higher", eduTestsVaccinated,
				"educ_other", eduTestsVaccinated
		)));


		Map<LocalDate, Double> leisureTestsPCR = new HashMap<LocalDate, Double>();
		Map<LocalDate, Double> workTestsPCR = new HashMap<LocalDate, Double>();
		Map<LocalDate, Double> kigaPramaryTestsPCR = new HashMap<LocalDate, Double>();
		Map<LocalDate, Double> eduTestsPCR = new HashMap<LocalDate, Double>();

		leisureTestsPCR.put(LocalDate.parse("2020-01-01"), 0.);
		workTestsPCR.put(LocalDate.parse("2020-01-01"), 0.);
		kigaPramaryTestsPCR.put(LocalDate.parse("2020-01-01"), 0.);
		eduTestsPCR.put(LocalDate.parse("2020-01-01"), 0.);

		kigaPramaryTestsPCR.put(LocalDate.parse("2021-05-10"), 0.4);

		if (params.testing.equals("no")) {
			leisureTestsPCR.put(restrictionDate, 0.0);
			workTestsPCR.put(restrictionDate, 0.0);
			kigaPramaryTestsPCR.put(restrictionDate, 0.0);
			eduTestsPCR.put(restrictionDate, 0.0);
		}


		pcrTest.setTestingRatePerActivityAndDate((Map.of(
				"leisure", leisureTestsPCR,
				"work", workTestsPCR,
				"business", workTestsPCR,
				"educ_kiga", kigaPramaryTestsPCR,
				"educ_primary", kigaPramaryTestsPCR,
				"educ_secondary", eduTestsPCR,
				"educ_tertiary", eduTestsPCR,
				"educ_higher", eduTestsPCR,
				"educ_other", eduTestsPCR
		)));

		Map<LocalDate, Double> leisureTestsPCRVaccinated = new HashMap<LocalDate, Double>();
		Map<LocalDate, Double> workTestsPCRVaccinated = new HashMap<LocalDate, Double>();
		Map<LocalDate, Double> eduTestsPCRVaccinated = new HashMap<LocalDate, Double>();
		leisureTestsPCRVaccinated.put(LocalDate.parse("2020-01-01"), 0.);
		workTestsPCRVaccinated.put(LocalDate.parse("2020-01-01"), 0.);
		eduTestsPCRVaccinated.put(LocalDate.parse("2020-01-01"), 0.);

		pcrTest.setTestingRatePerActivityAndDateVaccinated((Map.of(
				"leisure", leisureTestsPCRVaccinated,
				"work", workTestsPCRVaccinated,
				"business", workTestsPCRVaccinated,
				"educ_kiga", eduTestsPCRVaccinated,
				"educ_primary", eduTestsPCRVaccinated,
				"educ_secondary", eduTestsPCRVaccinated,
				"educ_tertiary", eduTestsPCRVaccinated,
				"educ_higher", eduTestsPCRVaccinated,
				"educ_other", eduTestsPCRVaccinated
		)));

		rapidTest.setTestingCapacity_pers_per_day(Map.of(
				LocalDate.of(1970, 1, 1), 0,
				testingStartDate, Integer.MAX_VALUE));

		pcrTest.setTestingCapacity_pers_per_day(Map.of(
				LocalDate.of(1970, 1, 1), 0,
				testingStartDate, Integer.MAX_VALUE));

		//tracing
		TracingConfigGroup tracingConfig = ConfigUtils.addOrGetModule(config, TracingConfigGroup.class);
		boolean qv = false;
		//		if (params.qV.equals("yes")) {
		//			qv = true;
		//		}
		tracingConfig.setQuarantineVaccinated((Map.of(
				episimConfig.getStartDate(), false
				//				restrictionDate, qv
		)));

		tracingConfig.setQuarantineDuration(Map.of(
				episimConfig.getStartDate(), 14,
				LocalDate.parse("2022-01-01"), 10
		));

		int greenPassValid = 90;
		int greenPassValidBoostered = Integer.MAX_VALUE;

		tracingConfig.setGreenPassValidDays(greenPassValid);
		tracingConfig.setGreenPassBoosterValidDays(greenPassValidBoostered);

		QuarantineStatus qs = QuarantineStatus.atHome;

		tracingConfig.setQuarantineStatus(Map.of(
				episimConfig.getStartDate(), QuarantineStatus.atHome
				//					restrictionDate, qs
		));


		return config;
	}


	private void configureBooster(VaccinationConfigGroup vaccinationConfig, double boosterSpeed, int boostAfter) {

		Map<LocalDate, Integer> boosterVaccinations = new HashMap<>();

		boosterVaccinations.put(LocalDate.parse("2020-01-01"), 0);

		boosterVaccinations.put(LocalDate.parse("2022-02-15"), (int) (2_352_480 * 0.04 * boosterSpeed / 7));
		boosterVaccinations.put(LocalDate.parse("2022-06-30"), 0);

		vaccinationConfig.setReVaccinationCapacity_pers_per_day(boosterVaccinations);


		vaccinationConfig.getOrAddParams(VaccinationType.mRNA)
				.setBoostWaitPeriod(boostAfter * 30 + 6 * 7);
		;

		vaccinationConfig.getOrAddParams(VaccinationType.omicronUpdate)
				.setBoostWaitPeriod(boostAfter * 30 + 6 * 7);
		;

		vaccinationConfig.getOrAddParams(VaccinationType.vector)
				.setBoostWaitPeriod(boostAfter * 30 + 9 * 7);
		;
	}

	public static final class Params {

		@GenerateSeeds(5)
		public long seed;

		// new
		@Parameter({0.0, 0.1, 0.2, 0.3, 0.4})
		double immuneShare;

		@StringParameter({"2021-11-15"})
		String immuneDate;

		@StringParameter({"2021-11-19"})
		String ba1Date;

		@StringParameter({"2021-12-22"})
		String ba2Date;

		@StringParameter({"true"})
		String ba1ba2x;

		@Parameter({0.01, 0.1, 1.0})
		Double immuneRespLow;

		@Parameter({1.0, 10., 100.})
		Double immuneRespHigh;

		@Parameter({2.7, 3.0, 3.3})
		double ba1Inf;

		@Parameter({1.5})
		double ba2Inf;

		@StringParameter({"current"})
		String testing;

	}

	public static void main(String[] args) {
		String[] args2 = {
				RunParallel.OPTION_SETUP, CologneJR.class.getName(),
				RunParallel.OPTION_PARAMS, Params.class.getName(),
				RunParallel.OPTION_TASKS, Integer.toString(1),
				RunParallel.OPTION_ITERATIONS, Integer.toString(1),
				RunParallel.OPTION_METADATA
		};

		RunParallel.main(args2);
	}


}

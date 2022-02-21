package org.matsim.episim.model;

import com.google.inject.Inject;
import org.apache.commons.math3.distribution.NormalDistribution;
import org.apache.commons.math3.distribution.RealDistribution;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.episim.*;
import org.matsim.episim.policy.Restriction;

import java.util.*;

/**
 * Extension of the {@link DefaultInfectionModel}, with age, time and seasonality-dependen additions.
 */
public final class InfectionModelWithAntibodies implements InfectionModel {

	private final FaceMaskModel maskModel;
	private final ProgressionModel progression;
	private final EpisimConfigGroup episimConfig;
	private final EpisimReporting reporting;
	private final SplittableRandom rnd;
	private final VaccinationConfigGroup vaccinationConfig;
	private final VirusStrainConfigGroup virusStrainConfig;

	private final double[] susceptibility = new double[128];
	private final double[] infectivity = new double[susceptibility.length];
	private final RealDistribution distribution;

	/**
	 * Scale infectivity to 1.0
	 */
	private final double scale;

	private double outdoorFactor;
	private int iteration;
	private double lastUnVac;

	@Inject
	InfectionModelWithAntibodies(FaceMaskModel faceMaskModel, ProgressionModel progression,
															Config config, EpisimReporting reporting, SplittableRandom rnd) {
		this.maskModel = faceMaskModel;
		this.progression = progression;
		this.episimConfig = ConfigUtils.addOrGetModule(config, EpisimConfigGroup.class);
		this.vaccinationConfig = ConfigUtils.addOrGetModule(config, VaccinationConfigGroup.class);
		this.virusStrainConfig = ConfigUtils.addOrGetModule(config, VirusStrainConfigGroup.class);
		this.reporting = reporting;
		this.rnd = rnd;

		// pre-compute interpolated age dependent entries
		for (int i = 0; i < susceptibility.length; i++) {
			susceptibility[i] = EpisimUtils.interpolateEntry(episimConfig.getAgeSusceptibility(), i);
			infectivity[i] = EpisimUtils.interpolateEntry(episimConfig.getAgeInfectivity(), i);
		}
		// based on https://arxiv.org/abs/2007.06602
		distribution = new NormalDistribution(0.5, 2.6);
		scale = 1 / distribution.density(distribution.getNumericalMean());
	}

	@Override
	public void setIteration(int iteration) {
		this.outdoorFactor = InfectionModelWithSeasonality.interpolateOutdoorFraction(episimConfig, iteration);
		this.iteration = iteration;
		reporting.reportOutdoorFraction(this.outdoorFactor, iteration);

	}

	@Override
	public double getLastUnVacInfectionProbability() {
		return lastUnVac;
	}

	@Override
	public double calcInfectionProbability(EpisimPerson target, EpisimPerson infector, Map<String, Restriction> restrictions,
										   EpisimConfigGroup.InfectionParams act1, EpisimConfigGroup.InfectionParams act2,
										   double contactIntensity, double jointTimeInContainer) {

		//noinspection ConstantConditions 		// ci corr can not be null, because sim is initialized with non null value
		double ciCorrection = Math.min(restrictions.get(act1.getContainerName()).getCiCorrection(), restrictions.get(act2.getContainerName()).getCiCorrection());

		double susceptibility = this.susceptibility[target.getAge()];
		double infectivity = this.infectivity[infector.getAge()];

		VirusStrainConfigGroup.StrainParams strain = virusStrainConfig.getParams(infector.getVirusStrain());

		double relativeAntibodyLevelTarget = getRelativeAntibodyLevel(target, iteration, target.getNumVaccinations(), target.getNumInfections(), infector.getVirusStrain(), vaccinationConfig);
		// the current infection is subtracted because it does not yet provide protection.
		double relativeAntibodyLevelInfector = getRelativeAntibodyLevel(infector, iteration, infector.getNumVaccinations(), infector.getNumInfections() - 1, infector.getVirusStrain(), vaccinationConfig);
		double indoorOutdoorFactor = InfectionModelWithSeasonality.getIndoorOutdoorFactor(outdoorFactor, rnd, act1, act2);
		double shedding = maskModel.getWornMask(infector, act2, restrictions.get(act2.getContainerName())).shedding;
		double intake = maskModel.getWornMask(target, act1, restrictions.get(act1.getContainerName())).intake;
		
		//reduced infectivity if infector has antibodies
		infectivity *= 1.0 - (0.25 * (1.0 - 1.0 / (1.0 + Math.pow(relativeAntibodyLevelInfector, vaccinationConfig.getBeta()))));
		
		// An infection always protects against further infections with the same variant for 3 months. 
		for (int infection = 0; infection < target.getNumInfections(); infection++) {
 			if (target.getVirusStrain(infection) == infector.getVirusStrain() && target.daysSinceInfection(infection, iteration) <= 90) {
 				susceptibility = 0.0;
 				break;
 			}
 		} 

		lastUnVac = calcUnVacInfectionProbability(target, infector, restrictions, act1, act2, contactIntensity, jointTimeInContainer, indoorOutdoorFactor, shedding, intake, infectivity, vaccinationConfig);
		double immunityFactor = 1.0 / (1.0 + Math.pow(relativeAntibodyLevelTarget, vaccinationConfig.getBeta()));
		target.setImmunityFactor(immunityFactor);

		return 1 - Math.exp(-episimConfig.getCalibrationParameter() * susceptibility * infectivity * contactIntensity * jointTimeInContainer * ciCorrection
				* target.getSusceptibility()
				* getInfectivity(infector)
				* strain.getInfectiousness()
				* shedding
				* intake
				* indoorOutdoorFactor
				* immunityFactor
		);
	}

	private static double getAk50( EpisimPerson target, VirusStrain strain, final VaccinationConfigGroup vaccinationConfig, int numInfections ) {
		var ak50PerStrain = vaccinationConfig.getAk50PerStrain();

		double ak50 = ak50PerStrain.get(strain );
		
		if (strain == VirusStrain.SARS_CoV_2 || strain == VirusStrain.ALPHA || strain == VirusStrain.DELTA) 
			return ak50;
		
		if (target.hadVaccinationType(VaccinationType.omicronUpdate) && (strain == VirusStrain.OMICRON_BA1 || strain == VirusStrain.OMICRON_BA2)) 
			return ak50PerStrain.get(VirusStrain.DELTA );
		
		boolean hadStrain = false;
		for (int idx = 0; idx<numInfections; idx++) {
			VirusStrain infection = target.getVirusStrain(idx);
			if (infection == strain) {
				hadStrain = true;
				break;
			}
		}
		
		if (hadStrain)
			return ak50PerStrain.get(VirusStrain.DELTA );
		
		if (target.hadVaccinationType(VaccinationType.omicronUpdate) && (strain == VirusStrain.STRAIN_A)) 
			return ak50PerStrain.get(VirusStrain.OMICRON_BA2 );
		
		return ak50;
	}

	public static double getRelativeAntibodyLevel( EpisimPerson target, int iteration, int numVaccinations, int numInfections, VirusStrain infectionStrain, VaccinationConfigGroup vaccinationConfig ) {

		//no antibodies
		if (numInfections == 0 && numVaccinations == 0) {
			return 0.0;
		}
		
		//an omicron infection alone does not protect against other strains
//		if (numVaccinations == 0 && strain != VirusStrain.OMICRON_BA1 && strain != VirusStrain.OMICRON_BA2) {
//			boolean hadNonOmicronInfection = false;
//			for (int idx = 0; idx<numInfections; idx++) {
//				VirusStrain infection = target.getVirusStrain(idx);
//				if (infection != VirusStrain.OMICRON_BA1 && infection != VirusStrain.OMICRON_BA2)
//					hadNonOmicronInfection = true;
//			}
//			if (!hadNonOmicronInfection)
//				return 0.0;
//		}
		
		//a ba.1 infection alone does not protect agains ba.2 and vice versa
//		if (numVaccinations == 0 && (strain == VirusStrain.OMICRON_BA1 || strain == VirusStrain.OMICRON_BA2)) {
//			boolean hadNonOmicronInfection = false;
//			boolean hadBa1Infection = false;
//			boolean hadBa2Infection = false;
//
//			for (int idx = 0; idx<numInfections; idx++) {
//				VirusStrain infection = target.getVirusStrain(idx);
//				if (infection == VirusStrain.OMICRON_BA1) 
//					hadBa1Infection = true;
//				else if (infection == VirusStrain.OMICRON_BA2) 
//					hadBa2Infection = true;
//				else
//					hadNonOmicronInfection = true;
//			}
//			if (!hadNonOmicronInfection) {
//				if (strain == VirusStrain.OMICRON_BA1 && !hadBa1Infection) {
//					return 0.0;
//				}
//				if (strain == VirusStrain.OMICRON_BA2 && !hadBa2Infection) {
//					return 0.0;
//				}
//			}	
//		}

		var ak50PerStrain = vaccinationConfig.getAk50PerStrain();

		// generate the immunity events:
		Map<Integer, VaccinationType> immunityEvents = new HashMap<>();
		for (int idx = 0; idx<numInfections; idx++) {
			int daysSinceInfection = target.daysSinceInfection(idx, iteration);
			int infectionDay = iteration - daysSinceInfection;
			immunityEvents.put(infectionDay, VaccinationType.natural);
		}
		for (int idx = 0; idx<numVaccinations; idx++) {
			int daysSinceVaccination = target.daysSinceVaccination(idx, iteration);
			int vaccinationDay = iteration - daysSinceVaccination;
			immunityEvents.put(vaccinationDay, target.getVaccinationType(idx));
		}

		double halfLife_days = 80.;

//		final Map<VaccinationType, Double> initalAntibodies = Map.of(
//				VaccinationType.generic, 1.0,
//				VaccinationType.natural, 1.0,
//				VaccinationType.mRNA, 2.0,
//				VaccinationType.omicronUpdate, 2.0,
//				VaccinationType.vector, 0.5
//									     );

//		double ba2Ak50 = 2.5 * 1.4;
//		ak50PerStrain.put(VirusStrain.SARS_CoV_2, 0.2);
//		ak50PerStrain.put(VirusStrain.ALPHA, 0.2);
//		ak50PerStrain.put(VirusStrain.DELTA, 0.5);
//		ak50PerStrain.put(VirusStrain.OMICRON_BA1, 2.5);
//		ak50PerStrain.put(VirusStrain.OMICRON_BA2, ba2Ak50);

//
//		final Map<VaccinationType, Double> antibodyFactor = Map.of(
//				VaccinationType.generic,10.0,
//				VaccinationType.natural, 10.0,
//				VaccinationType.mRNA, 20.0,
//				VaccinationType.omicronUpdate, 20.0,
//				VaccinationType.vector, 5.0
//		);

//		double antibodyLevel = 0.0;

		// now play back the immunization history:
		Map<VirusStrain,Double> antibodyLevelsAgainst = new LinkedHashMap<>();
		for (int day = 0; day<=iteration; day++) {
			if (immunityEvents.containsKey(day)) {
				VaccinationType immunizationBy = immunityEvents.get(day);
				if ( antibodyLevelsAgainst.isEmpty() ) {
					// 1st immunization:
					if( immunizationBy == VaccinationType.generic ){
						initializeFor1stGenVaccines( antibodyLevelsAgainst, 1.0, vaccinationConfig );
					} else if( immunizationBy == VaccinationType.mRNA ){
						initializeFor1stGenVaccines( antibodyLevelsAgainst, 2.0, vaccinationConfig );
					} else if( immunizationBy == VaccinationType.vector ){
						initializeFor1stGenVaccines( antibodyLevelsAgainst, 0.5, vaccinationConfig );
					} else if( immunizationBy == VaccinationType.omicronUpdate ){
						initializeFor1stGenVaccines( antibodyLevelsAgainst, 2.0, vaccinationConfig );
						antibodyLevelsAgainst.put( VirusStrain.OMICRON_BA1, 2.0/0.2 );
						antibodyLevelsAgainst.put( VirusStrain.OMICRON_BA2, 2.0/0.2 );
					} else if( immunizationBy == VaccinationType.natural ){
						initializeFor1stGenVaccines( antibodyLevelsAgainst, 1.0, vaccinationConfig );
					} else if( immunizationBy == VaccinationType.naturalWithOmicron ){
						initializeFor1stGenVaccines( antibodyLevelsAgainst, 0.0, vaccinationConfig );
						antibodyLevelsAgainst.put( VirusStrain.OMICRON_BA1, ??);
						antibodyLevelsAgainst.put( VirusStrain.OMICRON_BA2, ??);
					} else{
						throw new IllegalStateException( "Unexpected value: " + immunizationBy );
					}
				} else {
					// boost
					if( immunizationBy == VaccinationType.generic ){
					} else if( immunizationBy == VaccinationType.mRNA ){
						refresh( antibodyLevelsAgainst, 20, vaccinationConfig );
					} else if( immunizationBy == VaccinationType.vector ){
						refresh( antibodyLevelsAgainst, 5, vaccinationConfig );
					} else if( immunizationBy == VaccinationType.omicronUpdate ){
						refresh( antibodyLevelsAgainst, 20, vaccinationConfig );
					} else if( immunizationBy == VaccinationType.natural ){
						refresh( antibodyLevelsAgainst, 10, vaccinationConfig );
					} else if( immunizationBy == VaccinationType.naturalWithOmicron ){
						initializeFor1stGenVaccines( antibodyLevelsAgainst, 0.0, vaccinationConfig );
						antibodyLevelsAgainst.put( VirusStrain.OMICRON_BA1, ??);
						antibodyLevelsAgainst.put( VirusStrain.OMICRON_BA2, ??);
					} else{
						throw new IllegalStateException( "Unexpected value: " + immunizationBy );
					}

				}
//				if (antibodyLevel == 0.0){
//					antibodyLevel = initalAntibodies.get( immunizationBy );
//				} else {
//					antibodyLevel *= antibodyFactor.get(immunizationBy);
//
//					// we get at least as much as if this was the 1st immunization:
//					antibodyLevel = Math.max(initalAntibodies.get(immunizationBy), antibodyLevel);
//				}
//
//				// saturates at 20 (6000/300):
//				antibodyLevel = Math.min(20.0, antibodyLevel);
			}
			else {
				// exponential decay, day by day:
				antibodyLevelsAgainst.replaceAll( ( s, v ) -> antibodyLevelsAgainst.get( s ) * Math.pow( 0.5, 1 / halfLife_days ) );
			}
		}
		
//		double ak50 = getAk50(target, infectionStrain, vaccinationConfig, numInfections );

		return antibodyLevelsAgainst.get( infectionStrain );
	}
	private static void refresh( Map<VirusStrain, Double> antibodyLevelsAgainst, int vaccineTypeFactor, VaccinationConfigGroup vaccinationConfig ){
		for( VirusStrain strain : VirusStrain.values() ){
			switch( strain ) {
				case SARS_CoV_2:
					antibodyLevelsAgainst.put( strain, Math.min( 20., antibodyLevelsAgainst.get( strain ) * vaccineTypeFactor ) ) ;
					break;
				case ALPHA:
					antibodyLevelsAgainst.put( strain, Math.min( 20., antibodyLevelsAgainst.get( strain ) * vaccineTypeFactor ) ) ;
					break;
				case DELTA:
					antibodyLevelsAgainst.put( strain, Math.min( 20., antibodyLevelsAgainst.get( strain ) * vaccineTypeFactor ) ) ;
					break;
				case OMICRON_BA1:
					antibodyLevelsAgainst.put( strain, Math.min( 20., antibodyLevelsAgainst.get( strain ) * vaccineTypeFactor ) ) ;
					break;
				case OMICRON_BA2:
					antibodyLevelsAgainst.put( strain, Math.min( 20., antibodyLevelsAgainst.get( strain ) * vaccineTypeFactor ) ) ;
					break;
				default:
					antibodyLevelsAgainst.put( strain, Double.NaN );
			}
		}
	}
	private static void initializeFor1stGenVaccines( Map<VirusStrain, Double> antibodyLevelsAgainst, double vaccineTypeFactor, VaccinationConfigGroup vaccinationConfig ){
		var ak50PerStrain = vaccinationConfig.getAk50PerStrain();
		for( VirusStrain strain1 : VirusStrain.values() ){
			switch ( strain1 ){
				case SARS_CoV_2:
				case ALPHA:
					antibodyLevelsAgainst.put( strain1, vaccineTypeFactor / ak50PerStrain.get(strain1 ) ); // those two lead to same result according to what Sebastian had before
					break;
				case DELTA:
					antibodyLevelsAgainst.put( strain1, vaccineTypeFactor / ak50PerStrain.get(strain1 ) );
					break;
				case OMICRON_BA1:
					antibodyLevelsAgainst.put( strain1, vaccineTypeFactor / ak50PerStrain.get(strain1 ) );
					break;
				case OMICRON_BA2:
					antibodyLevelsAgainst.put( strain1, vaccineTypeFactor / ak50PerStrain.get(strain1 ) );
					break;
				default:
					antibodyLevelsAgainst.put( strain1, Double.NaN );
			}
		}
	}

	private double calcUnVacInfectionProbability( EpisimPerson target, EpisimPerson infector, Map<String, Restriction> restrictions, EpisimConfigGroup.InfectionParams act1, EpisimConfigGroup.InfectionParams act2, double contactIntensity, double jointTimeInContainer,
						      double indoorOutdoorFactor, double shedding, double intake, double infectivity, VaccinationConfigGroup vaccinationConfig ) {
		
		//noinspection ConstantConditions 		// ci corr can not be null, because sim is initialized with non null value
		double ciCorrection = Math.min(restrictions.get(act1.getContainerName()).getCiCorrection(), restrictions.get(act2.getContainerName()).getCiCorrection());

		double susceptibility = this.susceptibility[target.getAge()];

		VirusStrainConfigGroup.StrainParams strain = virusStrainConfig.getParams(infector.getVirusStrain());

		double relativeAntibodyLevel = getRelativeAntibodyLevel(target, iteration, 0, target.getNumInfections(), infector.getVirusStrain(), vaccinationConfig );
		
		// An infection always protects against further infections with the same variant for 3 months. 
		for (int infection = 0; infection < target.getNumInfections(); infection++) {
 			if (target.getVirusStrain(infection) == infector.getVirusStrain() && target.daysSinceInfection(infection, iteration) <= 90) {
 				susceptibility = 0.0;
 				break;
 			}
 		}
		
		return 1 - Math.exp(-episimConfig.getCalibrationParameter() * susceptibility * infectivity * contactIntensity * jointTimeInContainer * ciCorrection
				* target.getSusceptibility()
				* getInfectivity(infector)
				* strain.getInfectiousness()
				* shedding
				* intake
				* indoorOutdoorFactor
				/ (1.0 + Math.pow(relativeAntibodyLevel, this.vaccinationConfig.getBeta() ))

		);
	}

	/**
	 * Calculates infectivity of infector depending on disease progression.
	 *
	 * @apiNote package private for testing
	 */
	double getInfectivity(EpisimPerson infector) {

		if (infector.getDiseaseStatus() == EpisimPerson.DiseaseStatus.showingSymptoms) {

			int afterSymptomOnset = infector.daysSince(EpisimPerson.DiseaseStatus.showingSymptoms, iteration);
			return distribution.density(afterSymptomOnset) * scale;
		} else if (infector.getDiseaseStatus() == EpisimPerson.DiseaseStatus.contagious) {

			EpisimPerson.DiseaseStatus nextDiseaseStatus = progression.getNextDiseaseStatus(infector.getPersonId());
			int transitionDays = progression.getNextTransitionDays(infector.getPersonId());
			int daysSince = infector.daysSince(infector.getDiseaseStatus(), iteration);
			if (nextDiseaseStatus == EpisimPerson.DiseaseStatus.showingSymptoms) {

				return distribution.density(transitionDays - daysSince) * scale;

			} else if (nextDiseaseStatus == EpisimPerson.DiseaseStatus.recovered) {

				// when next state is recovered the half of the interval is used
				return distribution.density(daysSince - transitionDays / 2.0) * scale;
			}
		}


		return 0.0;
	}

	public static void main(String[] args) {
		// test distribution
		NormalDistribution dist = new NormalDistribution(0.5, 2.6);

		for(int i = -5; i <= 10; i++) {
			System.out.println(i + " " + dist.density(i));
		}

	}
}

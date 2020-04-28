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
package org.matsim.run.modules;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.episim.EpisimConfigGroup;
import org.matsim.episim.policy.FixedPolicy;

import javax.inject.Singleton;

public class SnzScenario extends AbstractModule {

	public static final String[] DEFAULT_ACTIVITIES = {
			"pt", "work", "leisure", "educ_kiga", "educ_primary", "educ_secondary", "educ_higher", "shopping", "errands", "business"
	};

	public static void setContactIntensities(EpisimConfigGroup episimConfig) {
		episimConfig.getOrAddContainerParams("pt")
				.setContactIntensity(10.0);
		episimConfig.getOrAddContainerParams("tr")
				.setContactIntensity(10.0);
		episimConfig.getOrAddContainerParams("leisure")
				.setContactIntensity(5.0);
		episimConfig.getOrAddContainerParams("educ_kiga")
				.setContactIntensity(10.0);
		episimConfig.getOrAddContainerParams("educ_primary")
				.setContactIntensity(4.0);
		episimConfig.getOrAddContainerParams("educ_secondary")
				.setContactIntensity(2.0);
		episimConfig.getOrAddContainerParams("home")
				.setContactIntensity(3.0);
	}

	public static void addParams(EpisimConfigGroup episimConfig) {

		episimConfig.addContainerParams(new EpisimConfigGroup.InfectionParams("pt", "tr"));
		// regular out-of-home acts:
		episimConfig.addContainerParams(new EpisimConfigGroup.InfectionParams("work"));
		episimConfig.addContainerParams(new EpisimConfigGroup.InfectionParams("leisure"));
		episimConfig.addContainerParams(new EpisimConfigGroup.InfectionParams("educ_kiga"));
		episimConfig.addContainerParams(new EpisimConfigGroup.InfectionParams("educ_primary"));
		episimConfig.addContainerParams(new EpisimConfigGroup.InfectionParams("educ_secondary"));
		episimConfig.addContainerParams(new EpisimConfigGroup.InfectionParams("educ_higher"));
		episimConfig.addContainerParams(new EpisimConfigGroup.InfectionParams("shopping"));
		episimConfig.addContainerParams(new EpisimConfigGroup.InfectionParams("errands"));
		episimConfig.addContainerParams(new EpisimConfigGroup.InfectionParams("business"));

		episimConfig.addContainerParams(new EpisimConfigGroup.InfectionParams("home"));

	}

	@Provides
	@Singleton
	public Config config() {
		Config config = ConfigUtils.createConfig(new EpisimConfigGroup());

		config.plans().setInputFile("../shared-svn/projects/episim/matsim-files/snz/BerlinV2/episim-input/be_v2_snz_entirePopulation_emptyPlans_withDistricts.xml.gz");
//		config.plans().setInputFile("../shared-svn/projects/episim/matsim-files/snz/Berlin/episim-input/be_entirePopulation_noPlans_withDistricts.xml.gz");
//		config.plans().setInputFile("../shared-svn/projects/episim/matsim-files/snz/Munich/episim-input/mu_entirePopulation_noPlans.xml.gz");
//		config.plans().setInputFile("../shared-svn/projects/episim/matsim-files/snz/Heinsberg/Heinsberg/episim-input/he_entirePopulation_noPlans.xml.gz");
//		config.plans().setInputFile("./output/filteredPopulation.xml.gz");

		EpisimConfigGroup episimConfig = ConfigUtils.addOrGetModule(config, EpisimConfigGroup.class);

//		episimConfig.setInputEventsFile("../shared-svn/projects/episim/matsim-files/snz/Heinsberg/Heinsberg/episim-input/he_events_total.xml.gz");
//		episimConfig.setInputEventsFile("../shared-svn/projects/episim/matsim-files/snz/Munich/episim-input/mu_snz_episim_events.xml.gz");
//		episimConfig.setInputEventsFile("../shared-svn/projects/episim/matsim-files/snz/Berlin/episim-input/be_snz_episim_events.xml.gz");
		episimConfig.setInputEventsFile("../shared-svn/projects/episim/matsim-files/snz/BerlinV2/episim-input/be_v2_snz_episim_events.xml.gz");

//		episimConfig.setInputEventsFile("/Users/sebastianmuller/Documents/episim-original-data/20200410_CH2019.25pct.run1.output_events.reducedForEpisim.xml.gz");


		episimConfig.setFacilitiesHandling(EpisimConfigGroup.FacilitiesHandling.snz);

		episimConfig.setSampleSize(0.25);
		episimConfig.setCalibrationParameter(0.000_001_7);
		episimConfig.setInitialInfections(50);
		episimConfig.setInitialInfectionDistrict("Berlin");
		addParams(episimConfig);

		setContactIntensities(episimConfig);
		int offset = -6;

		episimConfig.setPolicy(FixedPolicy.class, FixedPolicy.config()
				.restrict(20 - offset, 0.95, "work")
				.restrict(22 - offset, 0.9, "work")
				.restrict(23 - offset, 0.85, "work")
				.restrict(25 - offset, 0.8, "work")
				.restrict(26 - offset, 0.65, "work")
				.restrict(27 - offset, 0.6, "work")
				.restrict(28 - offset, 0.55, "work")
				.restrict(31 - offset, 0.5, "work")
				.restrict(33 - offset, 0.45, "work")
				.restrict(15 - offset, 0.95, "shopping", "errands", "business")
				.open(17 - offset, "shopping", "errands", "business")
				.restrict(19 - offset, 0.95, "shopping", "errands", "business")
				.open(20 - offset, "shopping", "errands", "business")
				.restrict(26 - offset, 0.95, "shopping", "errands", "business")
				.restrict(27 - offset, 0.85, "shopping", "errands", "business")
				.restrict(28 - offset, 0.75, "shopping", "errands", "business")
				.restrict(29 - offset, 0.7, "shopping", "errands", "business")
				.restrict(31 - offset, 0.65, "shopping", "errands", "business")
				.restrict(33 - offset, 0.6, "shopping", "errands", "business")
				.restrict(35 - offset, 0.65, "shopping", "errands", "business")
				.restrict(36 - offset, 0.6, "shopping", "errands", "business")
				.restrict(40 - offset, 0.65, "shopping", "errands", "business")
				.restrict(45 - offset, 0.7, "shopping", "errands", "business")
				.restrict(46 - offset, 0.75, "shopping", "errands", "business")
				.restrict(48 - offset, 0.8, "shopping", "errands", "business")
				.restrict(49 - offset, 0.85, "shopping", "errands", "business")
				.restrict(50 - offset, 0.75, "shopping", "errands", "business")
				.restrict(55 - offset, 0.7, "shopping", "errands", "business")
				.restrict(18 - offset, 0.94, "leisure")
				.restrict(19 - offset, 0.87, "leisure")
				.restrict(20 - offset, 0.81, "leisure")
				.restrict(21 - offset, 0.74, "leisure")
				.restrict(22 - offset, 0.68, "leisure")
				.restrict(23 - offset, 0.61, "leisure")
				.restrict(24 - offset, 0.55, "leisure")
				.restrict(25 - offset, 0.49, "leisure")
				.restrict(26 - offset, 0.42, "leisure")
				.restrict(27 - offset, 0.36, "leisure")
				.restrict(28 - offset, 0.29, "leisure")
				.restrict(29 - offset, 0.23, "leisure")
				.restrict(30 - offset, 0.16, "leisure")
				.restrict(31 - offset, 0.1, "leisure")
				.restrict(23 - offset, 0.1, "educ_primary", "educ_kiga")
				.restrict(23 - offset, 0., "educ_secondary", "educ_higher")
//				.restrict(74 - offset, 0.5, "educ_primary", "educ_kiga")
				.build()
		);

		config.controler().setOutputDirectory("./output-belinV2-base-google-" + offset);

		return config;
	}
}
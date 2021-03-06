/* *********************************************************************** *
 * project: org.matsim.*
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2017 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package org.matsim.schools.openBerlinScenario;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.schools.SchoolPopulationDestinationChoiceAndIntegration;
import playground.vsp.corineLandcover.CORINELandCoverCoordsModifier;
import playground.vsp.openberlinscenario.cemdap.output.CemdapOutput2MatsimPlansConverter;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 	(1) read in plans file for children, containing nothing but attributes holding inforamtion about age and municipality
 * 	(2) run CORINELandCoverCoordsModifier in order to perform location choice for the home activity
 * 	(3) run SchoolPopulationDestinationChoiceAndIntegration in order to perform destination choice (school assignment) and create home-school-home plans and in order to integrate with adult population
 *
 *	In between, some preparation processes for the steps need to be performed..
 *
 * @author tschlenther
 */
class CreateSchoolPopulationFromCorineLandCoverCoords {

	private static final double SAMPLE_SIZE = 0.1;

	private static final String INPUT_PLANS_FILE = "../../svn/shared-svn/studies/countries/de/open_berlin_scenario/be_5/cemdap_input/500/plans_children.xml.gz";
	private static final String PLANS_RADY_FOR_CORINE = "../../svn/shared-svn/studies/countries/de/open_berlin_scenario/be_5/cemdap_input/500/plans_children_readyForCorine_10pct.xml.gz";
	private static final String CORINE_LANDCOVER_FILE = "../../svn/shared-svn/studies/countries/de/open_berlin_scenario/input/shapefiles/corine_landcover/corine_lancover_berlin-brandenburg_GK4.shp";

	private static final String INPUT_PLANS_BERLIN_ADULTS_1PCT = "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/berlin/berlin-v5.4-1pct/input/berlin-v5.4-1pct.plans.xml.gz";
	private static final String INPUT_PLANS_BERLIN_ADULTS_10PCT = "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/berlin/berlin-v5.4-10pct/input/berlin-v5.4-10pct.plans.xml.gz";

	private static final String INPUT_SCHOOL_FACILITIES = "../../svn/shared-svn/projects/episim/matsim-files/open_berlin/input/educFacilitiesAggregated_GK4.txt";
	private static final String ZONE_SHP = "../../svn/shared-svn/studies/countries/de/open_berlin_scenario/input/shapefiles/2016/gemeinden_Planungsraum_GK4.shp";
	private static final String ZONE_ID_TAG = "NR";

	private static final String OUTPUT_PLANS_ENTIRE_BLN = "../../svn/shared-svn/studies/countries/de/open_berlin_scenario/be_5/population/berlin-v5.4-10pct-plans-includingChildren-corineCoords.xml.gz";
	private static final String OUTPUT_PLANS_SCHOOLPOP = "../../svn/shared-svn/studies/countries/de/open_berlin_scenario/be_5/population/plans_500_onlyChildren_empty_corineCoords_10pct.xml.gz";

	public static void main(String[] args) {

		Population emptyChildren = PopulationUtils.readPopulation(INPUT_PLANS_FILE);

		PopulationUtils.sampleDown(emptyChildren, SAMPLE_SIZE);

	    assignDummyHomeActsAndWritePlans(emptyChildren, PLANS_RADY_FOR_CORINE);

	    boolean simplifyGeom = false;
	    boolean combiningGeoms = false;
	    boolean sameHomeActivity = true;
	    String homeActivityPrefix = "home";

	    Map<String, String> shapeFileToFeatureKey = new HashMap<>();
	    shapeFileToFeatureKey.put(ZONE_SHP, ZONE_ID_TAG);

		CORINELandCoverCoordsModifier plansFilterForCORINELandCover = new CORINELandCoverCoordsModifier(PLANS_RADY_FOR_CORINE, shapeFileToFeatureKey,
	            CORINE_LANDCOVER_FILE, simplifyGeom, combiningGeoms, sameHomeActivity, homeActivityPrefix);

		plansFilterForCORINELandCover.process();
		Population population = plansFilterForCORINELandCover.getPopulation();
		PopulationWriter writer = new PopulationWriter(population);
		preparePlansForSchoolPopulationCreation(population);

		writer.write(OUTPUT_PLANS_SCHOOLPOP);

//		Population population = PopulationUtils.readPopulation(OUTPUT_PLANS_SCHOOLPOP);


		//now run SchoolPopulationDestinationChoiceAndIntegration which will read facilities, assign schools and build plans and finally will merge the adult population with the school population
		try {
			SchoolPopulationDestinationChoiceAndIntegration.run(population,
					INPUT_PLANS_BERLIN_ADULTS_10PCT,
					INPUT_SCHOOL_FACILITIES,
					null,
					OUTPUT_PLANS_ENTIRE_BLN);

			//delete the file created in middle of the process - it is not needed any more
			new File(PLANS_RADY_FOR_CORINE).delete();
		} catch (IOException e) {
			e.printStackTrace();
		}

	}

    private static void assignDummyHomeActsAndWritePlans(Population population, String outputPlans){
		PopulationFactory pf = population.getFactory();
		population.getPersons().values().forEach(p -> createPlanAndDummyHomeAct(p, pf));
		new PopulationWriter(population).write(outputPlans);
	}

	private static void createPlanAndDummyHomeAct(Person p, PopulationFactory pf){
		p.getPlans().clear();
		Plan plan = pf.createPlan();
		Activity act = pf.createActivityFromCoord("home_child", new Coord(-1, -1));
		act.getAttributes().putAttribute(CemdapOutput2MatsimPlansConverter.activityZoneId_attributeKey, p.getAttributes().getAttribute("municipalityId"));
		plan.addActivity(act);

		p.addPlan(plan);
	}

	private static void preparePlansForSchoolPopulationCreation(Population population){
		for (Person person : population.getPersons().values()) {
			Activity act = (Activity) person.getSelectedPlan().getPlanElements().get(0);
			if(! act.getType().startsWith("home")) throw new IllegalStateException("first act type is not home for person " + person);
			if(act.getCoord() == null) throw new IllegalStateException("can not retrieve coord info for home act of person " + person);
			person.getAttributes().putAttribute("homeX", act.getCoord().getX());
			person.getAttributes().putAttribute("homeY", act.getCoord().getY());
			person.setSelectedPlan(null);
			person.getPlans().clear();
		}
	}
}

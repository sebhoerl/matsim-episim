package org.matsim.episim;

import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.*;
import org.matsim.api.core.v01.events.handler.ActivityEndEventHandler;
import org.matsim.api.core.v01.events.handler.ActivityStartEventHandler;
import org.matsim.api.core.v01.events.handler.PersonEntersVehicleEventHandler;
import org.matsim.api.core.v01.events.handler.PersonLeavesVehicleEventHandler;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.api.internal.HasPersonId;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.gbl.Gbl;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.episim.EpisimPerson.DiseaseStatus;
import org.matsim.episim.model.*;
import org.matsim.episim.policy.ShutdownPolicy;
import org.matsim.facilities.Facility;
import org.matsim.utils.objectattributes.attributable.Attributes;
import org.matsim.vehicles.Vehicle;

import java.util.*;

/**
 * Main event handler of episim.
 */
public final class InfectionEventHandler implements ActivityEndEventHandler, PersonEntersVehicleEventHandler, PersonLeavesVehicleEventHandler, ActivityStartEventHandler {
    // Some notes:

    // * Especially if we repeat the same events file, then we do not have complete mixing.  So it may happen that only some subpopulations gets infected.

    // * However, if with infection proba=1 almost everybody gets infected, then in our current setup (where infected people remain in the iterations),
    // this will also happen with lower probabilities, albeit slower.  This is presumably the case that we want to investigate.

    // * We seem to be getting two different exponential spreading rates.  With infection proba=1, the crossover is (currently) around 15h.

    // TODO

    // * yyyyyy There are now some things that depend on ID conventions.  We should try to replace them.  This presumably would mean to interpret
    //  additional events.  Those would need to be prepared for the "reduced" files.  kai, mar'20


    private static final Logger log = LogManager.getLogger(InfectionEventHandler.class);

    private final Map<Id<Person>, EpisimPerson> personMap = new LinkedHashMap<>();
    private final Map<Id<Vehicle>, EpisimVehicle> vehicleMap = new LinkedHashMap<>();
    private final Map<Id<Facility>, EpisimFacility> pseudoFacilityMap = new LinkedHashMap<>();

    /**
     * Holds the current restrictions in place for all the activities.
     */
    private final Map<String, ShutdownPolicy.Restriction> restrictions;

    /**
     * Policy that will be enforced at the end of each day.
     */
    private final ShutdownPolicy policy;

    /**
     * Progress of the sickness at the end of the day.
     */
    private final ProgressionModel progressionModel;

    /**
     * Models the process of persons infecting each other during activities.
     */
    private final InfectionModel infectionModel;

    /**
     * Scenario with population information.
     */
    private final Scenario scenario;

    private final EpisimConfigGroup episimConfig;
    private final EventsManager eventsManager;
    private final EpisimReporting reporting;
    private final Random rnd = new Random(1);

    private int cnt = 10;
    private int iteration = 0;

    /**
     * Most recent infection report.
     */
    private EpisimReporting.InfectionReport report;

    @Inject
    public InfectionEventHandler(Config config, Scenario scenario, EventsManager eventsManager) {
        this.episimConfig = ConfigUtils.addOrGetModule(config, EpisimConfigGroup.class);
        this.scenario = scenario;
        this.eventsManager = eventsManager;
        this.policy = episimConfig.createPolicyInstance();
        this.restrictions = episimConfig.createInitialRestrictions();
        this.reporting = new EpisimReporting(config);
        this.progressionModel = new DefaultProgressionModel(rnd, episimConfig);
        this.infectionModel = new DefaultInfectionModel(rnd, episimConfig, reporting,
                episimConfig.getPutTracablePersonsInQuarantine() == EpisimConfigGroup.PutTracablePersonsInQuarantine.yes);
    }

    /**
     * Whether {@code event} should be handled.
     *
     * @param actType activity type
     */
    public static boolean shouldHandleActivityEvent(HasPersonId event, String actType) {
        // ignore drt and stage activities
        return !event.getPersonId().toString().startsWith("drt") && !event.getPersonId().toString().startsWith("rt")
                && !TripStructureUtils.isStageActivityType(actType);
    }

    /**
     * Whether a Person event (e.g. {@link PersonEntersVehicleEvent} should be handled.
     */
    public static boolean shouldHandlePersonEvent(HasPersonId event) {
        // ignore pt drivers and drt
        String id = event.getPersonId().toString();
        return !id.startsWith("pt_pt") && !id.startsWith("pt_tr") && !id.startsWith("drt") && !id.startsWith("rt");
    }

    /**
     * Returns the last {@link EpisimReporting.InfectionReport}.
     */
    public EpisimReporting.InfectionReport getReport() {
        return report;
    }

    /**
     * Returns true if more iterations won't change the results anymore and the simulation is finished.
     */
    public boolean isFinished() {
        return iteration > 0 && !progressionModel.canProgress(report);
    }

    @Override
    public void handleEvent(ActivityEndEvent activityEndEvent) {
        double now = activityEndEvent.getTime();

        if (!shouldHandleActivityEvent(activityEndEvent, activityEndEvent.getActType())) {
            return;
        }

        EpisimPerson episimPerson = this.personMap.computeIfAbsent(activityEndEvent.getPersonId(), this::createPerson);
        Id<Facility> episimFacilityId = createEpisimFacilityId(activityEndEvent);

        if (iteration == 0) {
            EpisimFacility episimFacility = this.pseudoFacilityMap.computeIfAbsent(episimFacilityId, EpisimFacility::new);
            if (episimPerson.getFirstFacilityId() == null) {
                episimFacility.addPerson(episimPerson, 0);
            }
            infectionModel.infectionDynamicsFacility(episimPerson, episimFacility, now, activityEndEvent.getActType());
            episimFacility.removePerson(episimPerson.getPersonId());
            // has moved to reset
//            handleInitialInfections( now, episimPerson );
        } else {
            EpisimFacility episimFacility = ((EpisimFacility) episimPerson.getCurrentContainer());
            if (!episimFacility.equals(pseudoFacilityMap.get(episimFacilityId))) {
                throw new IllegalStateException("Something went wrong ...");
            }
            infectionModel.infectionDynamicsFacility(episimPerson, episimFacility, now, activityEndEvent.getActType());
            episimFacility.removePerson(episimPerson.getPersonId());
        }
        if (episimPerson.getCurrentPositionInTrajectory() == 0) {
            episimPerson.setFirstFacilityId(episimFacilityId.toString());
        }
        handlePersonTrajectory(episimPerson.getPersonId(), activityEndEvent.getActType());

    }

    @Override
    public void handleEvent(PersonEntersVehicleEvent entersVehicleEvent) {
        double now = entersVehicleEvent.getTime();

        if (!shouldHandlePersonEvent(entersVehicleEvent)) {
            return;
        }

        // find the person:
        EpisimPerson episimPerson = this.personMap.computeIfAbsent(entersVehicleEvent.getPersonId(), this::createPerson);

        // find the vehicle:
        EpisimVehicle episimVehicle = this.vehicleMap.computeIfAbsent(entersVehicleEvent.getVehicleId(), EpisimVehicle::new);

        // add person to vehicle and memorize entering time:
        episimVehicle.addPerson(episimPerson, now);

    }

    @Override
    public void handleEvent(PersonLeavesVehicleEvent leavesVehicleEvent) {
        double now = leavesVehicleEvent.getTime();

        if (!shouldHandlePersonEvent(leavesVehicleEvent)) {
            return;
        }

        // find vehicle:
        EpisimVehicle episimVehicle = this.vehicleMap.get(leavesVehicleEvent.getVehicleId());

        EpisimPerson episimPerson = episimVehicle.getPerson(leavesVehicleEvent.getPersonId());

        infectionModel.infectionDynamicsVehicle(episimPerson, episimVehicle, now);

        // remove person from vehicle:
        episimVehicle.removePerson(episimPerson.getPersonId());
    }

    @Override
    public void handleEvent(ActivityStartEvent activityStartEvent) {
        double now = activityStartEvent.getTime();

        if (!shouldHandleActivityEvent(activityStartEvent, activityStartEvent.getActType())) {
            return;
        }

        // find the person:
        EpisimPerson episimPerson = this.personMap.computeIfAbsent(activityStartEvent.getPersonId(), this::createPerson);

        // create pseudo facility id that includes the activity type:
        Id<Facility> episimFacilityId = createEpisimFacilityId(activityStartEvent);

        // find the facility
        EpisimFacility episimFacility = this.pseudoFacilityMap.computeIfAbsent(episimFacilityId, EpisimFacility::new);

        // add person to facility
        episimFacility.addPerson(episimPerson, now);

        episimPerson.setLastFacilityId(episimFacilityId.toString());

        handlePersonTrajectory(episimPerson.getPersonId(), activityStartEvent.getActType());

    }

    /**
     * Create a new person and lookup attributes from scenario.
     */
    private EpisimPerson createPerson(Id<Person> id) {

        Person person = scenario.getPopulation().getPersons().get(id);
        Attributes attrs;
        if (person != null) {
            attrs = person.getAttributes();
        } else {
            // TODO: should warn here, but would produce too many messages the moment
            attrs = new Attributes();
        }

        return new EpisimPerson(id, attrs, eventsManager);
    }

    private Id<Facility> createEpisimFacilityId(HasFacilityId event) {
        if (episimConfig.getFacilitiesHandling() == EpisimConfigGroup.FacilitiesHandling.snz) {
            return Id.create(event.getFacilityId(), Facility.class);
        } else if (episimConfig.getFacilitiesHandling() == EpisimConfigGroup.FacilitiesHandling.bln) {
            if (event instanceof ActivityStartEvent) {
                ActivityStartEvent theEvent = (ActivityStartEvent) event;
                return Id.create(theEvent.getActType().split("_")[0] + "_" + theEvent.getLinkId().toString(), Facility.class);
            } else if (event instanceof ActivityEndEvent) {
                ActivityEndEvent theEvent = (ActivityEndEvent) event;
                return Id.create(theEvent.getActType().split("_")[0] + "_" + theEvent.getLinkId().toString(), Facility.class);
            } else {
                throw new IllegalStateException("unexpected event type=" + ((Event) event).getEventType());
            }
        } else {
            throw new NotImplementedException(Gbl.NOT_IMPLEMENTED);
        }

    }

    private void handlePersonTrajectory(Id<Person> personId, String trajectoryElement) {
        EpisimPerson person = personMap.get(personId);
        if (person.getCurrentPositionInTrajectory() + 1 == person.getTrajectory().size()) {
            return;
        }
        person.setCurrentPositionInTrajectory(person.getCurrentPositionInTrajectory() + 1);
        if (iteration > 0) {
            return;
        }
        person.addToTrajectory(trajectoryElement);
    }

//    private void handleInitialInfections( double now, EpisimPerson personWrapper ) {
//        // initial infections:
//        if (cnt > 0) {
//            personWrapper.setDiseaseStatus( now , EpisimPerson.DiseaseStatus.infectedButNotContagious );
//            personWrapper.setInfectionDate(iteration);
//            log.warn(" person " + personWrapper.getPersonId() + " has initial infection");
//            cnt--;
//            if (scenario != null) {
//                final Person person = PopulationUtils.findPerson(personWrapper.getPersonId(), scenario);
//                if (person != null) {
//                    person.getAttributes().putAttribute(AgentSnapshotInfo.marker, true);
//                }
//            }
//        }
//    }

    private void handleInitialInfections() {
        if (this.iteration != 1) {
            return;
        }
        Object[] personArray = this.personMap.values().toArray();
        do {
            EpisimPerson randomPerson = (EpisimPerson) personArray[rnd.nextInt(personArray.length)];
            if (randomPerson.getDiseaseStatus() == DiseaseStatus.susceptible) {
                randomPerson.setDiseaseStatus(0, DiseaseStatus.infectedButNotContagious);
                randomPerson.setInfectionDate(0);
                log.warn(" person " + randomPerson.getPersonId() + " has initial infection");
                cnt--;
            }

        } while (cnt > 0);
    }


    @Override
    public void reset(int iteration) {

        for (EpisimPerson person : personMap.values()) {
            checkAndHandleEndOfNonCircularTrajectory(person);
            person.setCurrentPositionInTrajectory(0);
            progressionModel.updateState(person, iteration);
        }

        this.iteration = iteration;

        handleInitialInfections();

        this.report = reporting.createReport(personMap, iteration);

        reporting.reporting(report, iteration);

        ImmutableMap<String, ShutdownPolicy.Restriction> im = ImmutableMap.copyOf(this.restrictions);
        policy.updateRestrictions(report, im);
        infectionModel.setRestrictionsForIteration(iteration, im);
        reporting.reportRestrictions(restrictions, iteration);

    }

    private void checkAndHandleEndOfNonCircularTrajectory(EpisimPerson person) {
        Id<Facility> firstFacilityId = Id.create(person.getFirstFacilityId(), Facility.class);
        if (person.isInContainer()) {
            Id<?> lastFacilityId = person.getCurrentContainer().getContainerId();
            if (this.pseudoFacilityMap.containsKey(lastFacilityId) && !firstFacilityId.equals(lastFacilityId)) {
                EpisimFacility lastFacility = this.pseudoFacilityMap.get(lastFacilityId);
                infectionModel.infectionDynamicsFacility(person, lastFacility, (iteration + 1) * 86400d, person.getTrajectory().get(person.getTrajectory().size() - 1));
                lastFacility.removePerson(person.getPersonId());
                EpisimFacility firstFacility = this.pseudoFacilityMap.get(firstFacilityId);
                firstFacility.addPerson(person, (iteration + 1) * 86400d);
            }
            if (this.vehicleMap.containsKey(lastFacilityId)) {
                EpisimVehicle lastVehicle = this.vehicleMap.get(lastFacilityId);
                infectionModel.infectionDynamicsVehicle(person, lastVehicle, (iteration + 1) * 86400d);
                lastVehicle.removePerson(person.getPersonId());
                EpisimFacility firstFacility = this.pseudoFacilityMap.get(firstFacilityId);
                firstFacility.addPerson(person, (iteration + 1) * 86400d);
            }
        } else {
            EpisimFacility firstFacility = this.pseudoFacilityMap.get(firstFacilityId);
            firstFacility.addPerson(person, (iteration + 1) * 86400d);
        }
    }

    public Collection<EpisimPerson> getPersons() {
        // I have nothing against given out the map if someone needs it, but as long as nobody needs it, we can as well give out this partial view and thus
        // keep implemention options open.  kai, mar'20
        return Collections.unmodifiableCollection(personMap.values());
    }

    public static final class EpisimVehicle extends EpisimContainer<Vehicle> {
        EpisimVehicle(Id<Vehicle> vehicleId) {
            super(vehicleId);
        }
    }

    public static final class EpisimFacility extends EpisimContainer<Facility> {
        EpisimFacility(Id<Facility> facilityId) {
            super(facilityId);
        }
    }
}


package org.matsim.episim.model;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.population.PopulationUtils;
import org.matsim.episim.*;
import org.matsim.episim.policy.ShutdownPolicy;
import org.matsim.vis.snapshotwriters.AgentSnapshotInfo;

import java.util.Map;
import java.util.Random;


/**
 * Base implementation for infection dynamics.
 */
public abstract class AbstractInfectionModel implements InfectionModel {

    protected final Scenario scenario = null;
    protected final Random rnd;
    protected final EpisimConfigGroup episimConfig;
    private final EpisimReporting reporting;
    protected int iteration;
    private Map<String, ShutdownPolicy.Restriction> restrictions;

    AbstractInfectionModel( Random rnd, EpisimConfigGroup episimConfig, EpisimReporting reporting ) {
        this.rnd = rnd;
        this.episimConfig = episimConfig;
        this.reporting = reporting;
    }


    /**
     * Set the iteration number and restrictions that are in place.
     */
    @Override
    public final void setRestrictionsForIteration(int iteration, Map<String, ShutdownPolicy.Restriction> restrictions) {
        this.iteration = iteration;
        this.restrictions = restrictions;
    }

    /**
     * Sets the infection status of a person and reports the event.
     */
    void infectPerson( EpisimPerson personWrapper, EpisimPerson infector, double now, String infectionType ) {

        if (personWrapper.getDiseaseStatus() != EpisimPerson.DiseaseStatus.susceptible) {
            throw new IllegalStateException("Person to be infected is not susceptible. Status is=" + personWrapper.getDiseaseStatus());
        }
        if (infector.getDiseaseStatus() != EpisimPerson.DiseaseStatus.contagious) {
            throw new IllegalStateException("Infector is not contagious. Status is=" + infector.getDiseaseStatus());
        }
        if (personWrapper.getQuarantineStatus() != EpisimPerson.QuarantineStatus.no) {
            throw new IllegalStateException("Person to be infected is in quarantine.");
        }
        if (infector.getQuarantineStatus() != EpisimPerson.QuarantineStatus.no) {
            throw new IllegalStateException("Infector is in quarantine.");
        }
        if (!personWrapper.getCurrentContainer().equals(infector.getCurrentContainer())) {
            throw new IllegalStateException("Person and infector are not in same container!");
        }

        personWrapper.setDiseaseStatus(now, EpisimPerson.DiseaseStatus.infectedButNotContagious);

        // TODO: Currently not in use, is it still needed?
        // Necessary for the otfvis visualization (although it is unfortunately not working).  kai, apr'20
        if (scenario != null) {
            final Person person = PopulationUtils.findPerson(personWrapper.getPersonId(), scenario);
            if (person != null) {
                person.getAttributes().putAttribute(AgentSnapshotInfo.marker, true);
            }
        }

        personWrapper.setInfectionDate(iteration);

        reporting.reportInfection(personWrapper, infector, now, infectionType);
    }
    public Map<String, ShutdownPolicy.Restriction> getRestrictions(){
        return restrictions;
    }

    private static boolean activityRelevantForInfectionDynamics( EpisimPerson person, EpisimConfigGroup episimConfig,
                                                                 Map<String, ShutdownPolicy.Restriction> restrictions, Random rnd ) {
        String act = person.getTrajectory().get(person.getCurrentPositionInTrajectory());
        return actIsRelevant(act, episimConfig, restrictions, rnd );
    }

    private static boolean actIsRelevant( String act, EpisimConfigGroup episimConfig,
                                          Map<String, ShutdownPolicy.Restriction> restrictions, Random rnd ) {
        for (EpisimConfigGroup.InfectionParams infectionParams : episimConfig.getContainerParams().values()) {
            if (infectionParams.includesActivity(act)) {
                ShutdownPolicy.Restriction r = restrictions.get(infectionParams.getContainerName());
                // avoid use of rnd if outcome is known beforehand
                if (r.getRemainingFraction() == 1)
                    return true;
                if (r.getRemainingFraction() == 0)
                    return false;

                return rnd.nextDouble() < r.getRemainingFraction();
            }
        }

        throw new IllegalStateException(String.format("No restrictions known for activity %s. Please add prefix to one infection parameter.", act));
    }

    private static boolean tripRelevantForInfectionDynamics( EpisimPerson person, EpisimConfigGroup episimConfig,
                                                             Map<String, ShutdownPolicy.Restriction> restrictions, Random rnd ) {
        String lastAct = "";
        if (person.getCurrentPositionInTrajectory() != 0) {
            lastAct = person.getTrajectory().get(person.getCurrentPositionInTrajectory() - 1);
        }

        String nextAct = person.getTrajectory().get(person.getCurrentPositionInTrajectory());

        // TODO: tr is a hardcoded activity for "pt"
        // last activity is only considered if present
        return actIsRelevant("tr", episimConfig, restrictions, rnd ) && actIsRelevant(nextAct, episimConfig, restrictions,
                        rnd ) && (lastAct.isEmpty() || actIsRelevant(lastAct, episimConfig,
                        restrictions, rnd ));

    }

    /**
     * Checks whether person is relevant for tracking or for infection dynamics.  Currently, "relevant for infection dynamics" is a subset of "relevant for
     * tracking".  However, I am not sure if this will always be the case.  kai, apr'20
     * @noinspection BooleanMethodIsAlwaysInverted
     */
    static boolean personRelevantForTrackingOrInfectionDynamics( EpisimPerson person, EpisimContainer<?> container, EpisimConfigGroup episimConfig,
                                                                 Map<String, ShutdownPolicy.Restriction> restrictions, Random rnd ) {

        // Infected but not contagious persons are considered additionally
        if (!hasDiseaseStatusRelevantForInfectionDynamics(person ) &&
                person.getDiseaseStatus() != EpisimPerson.DiseaseStatus.infectedButNotContagious)
            return false;

        if (person.getQuarantineStatus() == EpisimPerson.QuarantineStatus.full) {
            return false;
        }

        if (container instanceof InfectionEventHandler.EpisimFacility && activityRelevantForInfectionDynamics(person, episimConfig, restrictions, rnd )) {
            return true;
        }
        if (container instanceof InfectionEventHandler.EpisimVehicle && tripRelevantForInfectionDynamics(person, episimConfig, restrictions, rnd )) {
            return true;
        }
        return false;
    }

    private static boolean hasDiseaseStatusRelevantForInfectionDynamics( EpisimPerson personWrapper ) {
        switch (personWrapper.getDiseaseStatus()) {
            case susceptible:
            case contagious:
                return true;

            case infectedButNotContagious:
            case recovered:
            case seriouslySick: // assume is in hospital
            case critical:
                return false;

            default:
                throw new IllegalStateException("Unexpected value: " + personWrapper.getDiseaseStatus());
        }
    }
    /**
     * This method checks whether person1 and person2 have relevant disease status for infection dynamics.
     * If not or if both have the same disease status, the return value is false.
     */
    static boolean personsCanInfectEachOther( EpisimPerson person1, EpisimPerson person2 ) {
        if (person1.getDiseaseStatus() == person2.getDiseaseStatus()) return false;
        return (hasDiseaseStatusRelevantForInfectionDynamics(person1 ) && hasDiseaseStatusRelevantForInfectionDynamics(person2 ));
    }
}

package org.matsim.episim.model;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.episim.*;

import java.util.ArrayList;
import java.util.Random;

import static org.matsim.episim.EpisimPerson.*;

/**
 * This infection model calculates the joint time two persons have been at the same place and calculates a infection probability according to:
 * <pre>
 *    1 - e^(calibParam * contactIntensity * jointTimeInContainer)
 * </pre>
 */
public final class DefaultInfectionModel extends AbstractInfectionModel {

    private static final Logger log = LogManager.getLogger(DefaultInfectionModel.class);

    /**
     * Flag to enable tracking, which is considerably slower.
     */
    private final boolean trackingEnabled;

    public DefaultInfectionModel(Random rnd, EpisimConfigGroup episimConfig, EpisimReporting reporting, boolean trackingEnabled) {
        super(rnd, episimConfig, reporting);
        this.trackingEnabled = trackingEnabled;
    }

    @Override
    public void infectionDynamicsVehicle(EpisimPerson personLeavingVehicle, InfectionEventHandler.EpisimVehicle vehicle, double now) {
        infectionDynamicsGeneralized(personLeavingVehicle, vehicle, now);
    }

    @Override
    public void infectionDynamicsFacility(EpisimPerson personLeavingFacility, InfectionEventHandler.EpisimFacility facility, double now, String actType) {
        infectionDynamicsGeneralized(personLeavingFacility, facility, now);
    }

    private void infectionDynamicsGeneralized(EpisimPerson personLeavingContainer, EpisimContainer<?> container, double now) {
        // yyyy Why is infectionSituaiton needed.  If we have the container, then we have the situation, don't we? kai, apr'20


        if (iteration == 0) {
            return;
        }

//        if (trackingEnabled && !personRelevantForTrackingOrInfectionDynamics(personLeavingContainer, container, episimConfig, getRestrictions(), rnd ))
//            return;
//        else if (!trackingEnabled && !personRelevantForInfectionDynamics(personLeavingContainer, container, episimConfig, getRestrictions(), rnd ))
//            return;

        if ( !personRelevantForTrackingOrInfectionDynamics( personLeavingContainer, container, episimConfig, getRestrictions(), rnd ) ) {
            return;
        }

        ArrayList<EpisimPerson> otherPersonsInContainer = new ArrayList<>(container.getPersons());
        otherPersonsInContainer.remove(personLeavingContainer);

        // For the time being, will just assume that the first 10 persons are the ones we interact with.  Note that because of
        // shuffle, those are 10 different persons every day.

        // persons are scaled to number of agents with sample size, but at least 3 for the small development scenarios
        int contactWith = Math.min(otherPersonsInContainer.size(), Math.max((int) (episimConfig.getSampleSize() * 10), 3));
        for (int ii = 0; ii < contactWith; ii++) {

            // we are essentially looking at the situation when the person leaves the container.  Interactions with other persons who have
            // already left the container were treated then.  In consequence, we have some "circle of persons around us" (yyyy which should
            //  depend on the density), and then a probability of infection in either direction.

            // Draw the contact person and remove it -> we don't want to draw it multiple times
            EpisimPerson contactPerson = otherPersonsInContainer.remove(rnd.nextInt(otherPersonsInContainer.size()));

            // If tracking is not enabled, the loop can continue earlier
//            if (trackingEnabled && !personRelevantForTrackingOrInfectionDynamics(contactPerson, container, episimConfig, getRestrictions(), rnd )){
//                continue;
//            } else if (!trackingEnabled &&
//                    (personLeavingContainer.getDiseaseStatus() == contactPerson.getDiseaseStatus() ||
//                                     !personRelevantForTrackingOrInfectionDynamics(contactPerson, container, episimConfig, getRestrictions(), rnd ))) {
//                continue;
//            }

            if ( !personRelevantForTrackingOrInfectionDynamics( contactPerson, container, episimConfig, getRestrictions(), rnd ) ) {
                continue;
            }

            // we have thrown the random numbers, so we can bail out in some cases if we are not tracking:
            if ( !trackingEnabled ) {
                if ( personLeavingContainer.getDiseaseStatus()== DiseaseStatus.infectedButNotContagious ) {
                    continue;
                }
                if ( contactPerson.getDiseaseStatus()== DiseaseStatus.infectedButNotContagious ) {
                    continue;
                }
                if ( personLeavingContainer.getDiseaseStatus()==contactPerson.getDiseaseStatus() ) {
                    continue;
                }
            }

            // yyyy I don't like these separate if conditions for tracking vs without.  Too large danger that we get something wrong there.  kai, apr'20

            // yyyyyy I do not understand why the execution path has to be different for with tracking vs. without tracking.  Could you please explain?
            // (Maybe the logic is that one would note people for tracking even if they have the same disease status, since one would not know that.  Is that
            // the reason?  kai, apr'20)

            // Yes that is the reason. If both are suceptible for example, one might get infected later. Or consider persons in status of infectedButNotContagious.
            // In the end of the day, if they were the contact person of a person that got infected and tracked them,
            // they will be put in quarantine and can not infect other people in the following days.
            // If tracking is not enabled, we do not have to go into the for-loop for leaving persons with status infectedButNotContagious or do not have to perform
            // tracking inside the loop for contact persons with status infectedButNotContagious

            String leavingPersonsActivity = personLeavingContainer.getTrajectory().get(personLeavingContainer.getCurrentPositionInTrajectory());
            String otherPersonsActivity = contactPerson.getTrajectory().get(contactPerson.getCurrentPositionInTrajectory());

            String infectionType = getInfectionType(container, leavingPersonsActivity, otherPersonsActivity);

            //forbid certain cross-activity interactions, keep track of contacts
            if (container instanceof InfectionEventHandler.EpisimFacility) {
                //home can only interact with home or leisure
                if (infectionType.contains("home") && !infectionType.contains("leis") && !(leavingPersonsActivity.contains("home") && otherPersonsActivity.contains("home"))) {
                    continue;
                } else if (infectionType.contains("edu") && !infectionType.contains("work") && !(leavingPersonsActivity.contains("edu") && otherPersonsActivity.contains("edu"))) {
                    //edu can only interact with work or edu
                    continue;
                }
                if(trackingEnabled){
                    trackContactPerson(personLeavingContainer, contactPerson, leavingPersonsActivity);
                }
            }

            if (!AbstractInfectionModel.personsCanInfectEachOther(personLeavingContainer, contactPerson )) {
                continue;
            }

            Double containerEnterTimeOfPersonLeaving = container.getContainerEnteringTime(personLeavingContainer.getPersonId());
            Double containerEnterTimeOfOtherPerson = container.getContainerEnteringTime(contactPerson.getPersonId());

            // persons leaving their first-ever activity have no starting time for that activity.  Need to hedge against that.  Since all persons
            // start healthy (the first seeds are set at enterVehicle), we can make some assumptions.
            if (containerEnterTimeOfPersonLeaving == null && containerEnterTimeOfOtherPerson == null) {
                throw new IllegalStateException("should not happen");
                // null should only happen at first activity.  However, at first activity all persons are susceptible.  So the only way we
                // can get here is if an infected person entered the container and is now leaving again, while the other person has been in the
                // container from the beginning.  ????  kai, mar'20
            }
            if (containerEnterTimeOfPersonLeaving == null) {
                containerEnterTimeOfPersonLeaving = Double.NEGATIVE_INFINITY;
            }
            if (containerEnterTimeOfOtherPerson == null) {
                containerEnterTimeOfOtherPerson = Double.NEGATIVE_INFINITY;
            }

            double jointTimeInContainer = now - Math.max(containerEnterTimeOfPersonLeaving, containerEnterTimeOfOtherPerson);
            if (jointTimeInContainer < 0 || jointTimeInContainer > 86400) {
                log.warn(containerEnterTimeOfPersonLeaving);
                log.warn(containerEnterTimeOfOtherPerson);
                log.warn(now);
                throw new IllegalStateException("joint time in container is not plausible for personLeavingContainer=" + personLeavingContainer.getPersonId() + " and contactPerson=" + contactPerson.getPersonId() + ". Joint time is=" + jointTimeInContainer);
            }

            double contactIntensity = getContactIntensity(container, leavingPersonsActivity, otherPersonsActivity);


            double infectionProba = 1 - Math.exp(-episimConfig.getCalibrationParameter() * contactIntensity * jointTimeInContainer);
            // note that for 1pct runs, calibParam is of the order of one, which means that for typical times of 100sec or more,
            // exp( - 1 * 1 * 100 ) \approx 0, and thus the infection proba becomes 1.  Which also means that changes in contactIntensity has
            // no effect.  kai, mar'20

            if (rnd.nextDouble() < infectionProba) {
                if (personLeavingContainer.getDiseaseStatus() == DiseaseStatus.susceptible) {
                    infectPerson(personLeavingContainer, contactPerson, now, infectionType);
                    //TODO the fact that we return here creates a bug concerning tracking. we would need to draw the remaining number of contact persons before return. or have a separate boolean leavingPersonGotInfected
                    return;
                } else {
                    infectPerson(contactPerson, personLeavingContainer, now, infectionType);
                }
            }
        }
    }

    private String getInfectionType(EpisimContainer<?> container, String leavingPersonsActivity, String otherPersonsActivity) {
        String infectionType;
        if (container instanceof InfectionEventHandler.EpisimFacility) {
            infectionType = leavingPersonsActivity + "_" + otherPersonsActivity;
        }
        else if (container instanceof InfectionEventHandler.EpisimVehicle) {
            infectionType = "pt";
        }
        else {
            throw new RuntimeException("Infection situation is unknown");
        }
        return infectionType;
    }

    private double getContactIntensity(EpisimContainer<?> container, String leavingPersonsActivity, String otherPersonsActivity) {
        //maybe this can be cleaned up or summarized in some way
        double contactIntensity = -1;
        if (container instanceof InfectionEventHandler.EpisimVehicle) {
            String containerIdString = container.getContainerId().toString();

            for (EpisimConfigGroup.InfectionParams infectionParams : episimConfig.getContainerParams().values()) {
                if (infectionParams.includesActivity(containerIdString)) {
                    contactIntensity = infectionParams.getContactIntensity();
                }
            }
            if (contactIntensity < 0.) {
                throw new IllegalStateException("contactIntensity not defined for vehicle container=" + containerIdString + ".  There needs to be a config entry for each activity type.");
            }
        } else if (container instanceof InfectionEventHandler.EpisimFacility){
            double contactIntensityLeavingPerson = -1;
            double contactIntensityOtherPerson = -1;
            for (EpisimConfigGroup.InfectionParams infectionParams : episimConfig.getContainerParams().values()) {
                if (infectionParams.includesActivity(leavingPersonsActivity)) {
                    contactIntensityLeavingPerson = infectionParams.getContactIntensity();
                }
                if (infectionParams.includesActivity(otherPersonsActivity)) {
                    contactIntensityOtherPerson = infectionParams.getContactIntensity();
                }
            }
            if (contactIntensityLeavingPerson < 0. || contactIntensityOtherPerson < 0.) {
                throw new IllegalStateException("contactIntensity not defined either for activityType=" + contactIntensityLeavingPerson + " or for activityType= " + otherPersonsActivity
                        + ".  There needs to be a config entry for each activity type.");
            }

            contactIntensity = Math.max(contactIntensityLeavingPerson, contactIntensityOtherPerson);
        } else {
            throw new IllegalArgumentException("do not know how to deal container " + container);
        }
        return contactIntensity;
    }

    private void trackContactPerson(EpisimPerson personLeavingContainer, EpisimPerson otherPerson, String leavingPersonsActivity) {
        if (leavingPersonsActivity.contains("home") || leavingPersonsActivity.contains("work") || (leavingPersonsActivity.contains("leisure") && rnd.nextDouble() < 0.8)) {
            if (!personLeavingContainer.getTraceableContactPersons().contains(otherPerson)) { //if condition should not be necessary as it is a set
                personLeavingContainer.addTraceableContactPerson(otherPerson);
            }
            if (!otherPerson.getTraceableContactPersons().contains(personLeavingContainer)) { //if condition should not be necessary as it is a set
                otherPerson.addTraceableContactPerson(personLeavingContainer);
            }
        }
    }

}

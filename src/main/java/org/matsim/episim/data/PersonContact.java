package org.matsim.episim.data;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.episim.EpisimConfigGroup;

import java.io.DataInput;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Object representing the contact with a different person.
 * This class only contains partial information, i.e. as few as possible.
 * vInstances of this class must not be stored, only consumed!
 */
public interface PersonContact {

	/**
	 * Id of the contact person.
	 */
	Id<Person> getContactPerson();

	/**
	 * Activity that the other person performed.
	 */
	EpisimConfigGroup.InfectionParams getContactPersonActivity();

	/**
	 * Offset in seconds since entering the container when this contact can happen.
	 */
	int getOffset();

	/**
	 * Duration of the contact in seconds.
	 */
	int getDuration();

	/**
	 * Creates a new instance with simple representation.
	 */
	static PersonContact newInstance(Id<Person> contactPersonId, EpisimConfigGroup.InfectionParams params, int offset, int duration) {
		return new PersonContactImpl(contactPersonId, params, offset, duration);
	}

	/**
	 * Read contact from data input.
	 */
	static PersonContact read(DataInput in, Int2ObjectMap<Id<Person>> persons, EpisimConfigGroup.InfectionParams[] params) {
		try {
			return new PersonContactImpl(in, persons, params);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}

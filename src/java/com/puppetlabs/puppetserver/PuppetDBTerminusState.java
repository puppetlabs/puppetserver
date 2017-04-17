package com.puppetlabs.puppetserver;

import clojure.lang.Atom;

/**
  The PuppetDB terminus sometimes has occasion to store data that should be
  shared between all JRuby instances. This class provides a place to put it.
 */
public final class PuppetDBTerminusState {
    private static final Atom sharedStateAtom = new Atom(null);
    public static final Atom getSharedStateAtom() { return sharedStateAtom; }
}

/*
 * Copyright (C) 2020 The Baremaps Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.baremaps.osm.domain;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Objects;
import java.util.StringJoiner;

/** Represents a member of a relation in an OpenStreetMap dataset. */
public final class Member {

  public enum MemberType {
    NODE,
    WAY,
    RELATION;

    public static MemberType forNumber(int value) {
      switch (value) {
        case 0:
          return NODE;
        case 1:
          return WAY;
        case 2:
          return RELATION;
        default:
          throw new IllegalArgumentException();
      }
    }
  }

  private final long ref;

  private final MemberType type;

  private final String role;

  public Member(long ref, MemberType type, String role) {
    checkNotNull(type);
    checkNotNull(role);
    this.ref = ref;
    this.type = type;
    this.role = role;
  }

  public long getRef() {
    return ref;
  }

  public MemberType getType() {
    return type;
  }

  public String getRole() {
    return role;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    Member member = (Member) o;
    return ref == member.ref
        && Objects.equal(type, member.type)
        && Objects.equal(role, member.role);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(ref, type, role);
  }

  @Override
  public String toString() {
    return new StringJoiner(", ", Member.class.getSimpleName() + "[", "]")
        .add("ref=" + ref)
        .add("type='" + type.name() + "'")
        .add("role='" + role + "'")
        .toString();
  }
}

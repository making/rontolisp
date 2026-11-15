# sets: the examples of spec.md ("Sets", "set", the set· methods), and the cases around
# them

s1 = set(["a", "b", "c"])
assert_("a" in s1)
assert_("z" not in s1)
assert_eq(type(s1), "set")
assert_eq(list(set([3, 1, 1, 2])), [3, 1, 2])
assert_eq(set({"k1": "v1", "k2": "v2"}), set(["k1", "k2"]))
assert_eq(len(set()), 0)
assert_eq(repr(set()), "set()")
assert_eq(str(set([1, "a", (2,)])), 'set([1, "a", (2,)])')
assert_eq(set([1, 1.0, True]), set([1, True]))
assert_eq("non-empty" if set() else "empty", "empty")
assert_eq("non-empty" if set(["x", "y"]) else "empty", "non-empty")
assert_(set() == set())
assert_(set() != [])
assert_(set([1, 2]) == set([2, 1]))
assert_(set([1, 2]) != [1, 2])
assert_(set([1]) != {1: None})
assert_eq(list(set([1, 2]) | set([3, 2])), [1, 2, 3])
assert_eq(set([1, 2]) & set([2, 3]), set([2]))
assert_eq(set([1, 2]) & set([3, 4]), set())
assert_eq(set([1, 2]) - set([2, 3]), set([1]))
assert_eq(set([1, 2]) - set([3, 4]), set([1, 2]))
assert_eq(set([1, 2]) ^ set([2, 3]), set([1, 3]))
assert_eq(list(set([1, 2]) ^ set([3, 4])), [1, 2, 3, 4])
assert_eq(list(set([3, 1, 2]) ^ set([4, 1])), [3, 2, 4])
assert_eq(list(set([3, 1, 2]) & set([2, 3])), [3, 2])
assert_eq(sorted(set([3, 1, 2])), [1, 2, 3])
assert_eq(max(set([3, 1, 2])), 3)
assert_eq(set([1, 2, 3]).difference([2]), set([1, 3]))
assert_eq(set([1, 2, 3]).difference([0, 1], [3, 4]), set([2]))
assert_eq(set([1, 2]).intersection([2, 3]), set([2]))
assert_eq(set([1, 2, 3]).intersection([0, 1], [1, 2]), set([1]))
assert_eq(set([1, 2]).symmetric_difference([2, 3]), set([1, 3]))
assert_eq(list(set([1, 2]).union([2, 3])), [1, 2, 3])
assert_eq(set([1, 2]).union([2, 3], {3: "a", 4: "b"}), set([1, 2, 3, 4]))
assert_eq(set([1]).union(), set([1]))
assert_(set([1, 2]).isdisjoint([3]))
assert_(not set([1, 2]).isdisjoint([2]))
assert_(set([1]).issubset([1, 2]))
assert_(not set([1, 3]).issubset([1, 2]))
assert_(set([1, 2]).issuperset([2]))
assert_(not set([1]).issuperset([1, 2]))
assert_eq(dir(set())[:4], ["add", "clear", "difference", "difference_update"])

def in_place():
  s = set([1, 2])
  alias = s
  s |= set([2, 3, 4])
  assert_eq(s, set([1, 2, 3, 4]))
  s &= set([0, 1, 2, 3])
  assert_eq(s, set([1, 2, 3]))
  s -= set([0, 1])
  assert_eq(s, set([2, 3]))
  s ^= set([3, 4])
  assert_eq(s, set([2, 4]))
  assert_eq(alias, set([2, 4]))
  d = {"a": 1}
  same = d
  d |= {"b": 2}
  assert_eq(same, {"a": 1, "b": 2})
in_place()

def methods():
  s = set(["z", "y", "z", "y"])
  assert_eq(len(s), 2)
  assert_eq(s.add("x"), None)
  s.add("z")
  assert_eq(len(s), 3)
  assert_eq([e for e in s], ["z", "y", "x"])
  s = set([1, 2, 3, 4])
  assert_eq(s.difference_update([2]), None)
  assert_eq(s, set([1, 3, 4]))
  s.difference_update([0, 1], [4, 5])
  assert_eq(s, set([3]))
  s = set(["x", "y"])
  assert_eq(s.discard("y"), None)
  assert_eq(s, set(["x"]))
  s.discard("y")
  assert_eq(s, set(["x"]))
  s = set([1, 2, 3, 4])
  assert_eq(s.intersection_update([0, 1, 2]), None)
  assert_eq(s, set([1, 2]))
  s.intersection_update([0, 1], [1, 2])
  assert_eq(s, set([1]))
  s = set([3, 1, 2])
  assert_eq(s.pop(), 3)
  assert_eq(s.pop(), 1)
  assert_eq(s, set([2]))
  s = set([1, 2])
  assert_eq(s.remove(2), None)
  assert_eq(s, set([1]))
  s = set([1, 2])
  assert_eq(s.symmetric_difference_update([2, 3]), None)
  assert_eq(s, set([1, 3]))
  s = set()
  assert_eq(s.update([1, 2]), None)
  assert_eq(s, set([1, 2]))
  s.update([2, 3], [3, 4])
  assert_eq(list(s), [1, 2, 3, 4])
  assert_eq(s.clear(), None)
  assert_eq(s, set())
  add = s.add
  add(7)
  assert_eq(s, set([7]))
methods()
---
set([1]) < set([2]) ### unsupported comparison
---
sorted([set([1]), set([2])]) ### unsupported comparison
---
set([[1]]) ### unhashable
---
{set(): 1} ### unhashable
---
set([1]) | [2] ### unknown binary op
---
set(1) ### got int, want iterable
---
set().pop() ### empty set
---
set([1]).remove(2) ### not found
---
set([1]).discard([]) ### unhashable
---
set([1]).get(1) ### has no .get field or method
---
set([1])[0] ### unhandled index operation
---
set([1]).update(x = 1) ### does not accept keyword arguments
---
def f(s):
  for x in s:
    s.add(2)
f(set([1])) ### insert into set during iteration
---
def g(s):
  for x in s:
    s |= set([2])
g(set([1])) ### set during iteration

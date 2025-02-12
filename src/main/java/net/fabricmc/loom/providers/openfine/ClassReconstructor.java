package net.fabricmc.loom.providers.openfine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

public class ClassReconstructor {
	public static byte[] reconstruct(byte[] original, byte[] modified, byte[] server) {
		ClassNode originalClass = read(original);
		ClassNode patchedClass = read(modified);

		assert Objects.equals(originalClass.name, patchedClass.name);
		assert Objects.equals(originalClass.superName, patchedClass.superName);
		assert Objects.equals(originalClass.signature, patchedClass.signature);

		AccessChange accessChange = AccessChange.forAccess(originalClass.access, patchedClass.access);
		FinalityChange finalityChange = FinalityChange.forAccess(originalClass.access, patchedClass.access);

		Set<String> gainedInterfaces, lostInterfaces;
		if (!Objects.equals(originalClass.interfaces, patchedClass.interfaces)) {
			gainedInterfaces = Sets.difference(ImmutableSet.copyOf(patchedClass.interfaces), ImmutableSet.copyOf(originalClass.interfaces));
			lostInterfaces = Sets.difference(ImmutableSet.copyOf(originalClass.interfaces), ImmutableSet.copyOf(patchedClass.interfaces));
		} else {
			gainedInterfaces = lostInterfaces = Collections.emptySet();
		}

		assert Objects.equals(originalClass.outerClass, patchedClass.outerClass);
		assert Objects.equals(originalClass.outerMethod, patchedClass.outerMethod);
		assert Objects.equals(originalClass.outerMethodDesc, patchedClass.outerMethodDesc);

		assert Objects.equals(originalClass.nestHostClass, patchedClass.nestHostClass);
		assert Objects.equals(originalClass.nestMembers, patchedClass.nestMembers);

		Annotator annotator = new Annotator(accessChange, finalityChange, gainedInterfaces, lostInterfaces);

		MethodChanges methodChanges = new MethodChanges(originalClass.name, originalClass.methods, patchedClass.methods);
		if (methodChanges.couldNeedLambdasFixing()) {
			Map<String, String> lambdaFixes = new HashMap<>();
			methodChanges.tryFixLambdas(lambdaFixes);

			if (!lambdaFixes.isEmpty()) fixLambdas(lambdaFixes, originalClass.methods, patchedClass.methods, methodChanges);
		}
		methodChanges.annotate(annotator);

		FieldChanges fieldChanges = new FieldChanges(originalClass.name, originalClass.fields, patchedClass.fields);
		fieldChanges.annotate(annotator);

		annotator.apply(patchedClass);
		rebuildOrder(patchedClass, server);
		return write(patchedClass);
	}

	private static ClassNode read(byte[] data) {
		ClassNode node = new ClassNode();
		new ClassReader(data).accept(node, 0/*ClassReader.EXPAND_FRAMES*/);
		return node;
	}

	private static void fixLambdas(Map<String, String> fixes, List<MethodNode> originalMethods, List<MethodNode> patchedMethods, MethodChanges changes) {
		changes.sortModifiedMethods(patchedMethods);
		patchedMethods.forEach(method -> MethodComparison.findLambdas(method.instructions, 0, idin -> {
			Handle handle = (Handle) idin.bsmArgs[1];
			String remap = fixes.get(handle.getOwner() + '#' + handle.getName() + handle.getDesc());

			if (remap != null) {
				int split = remap.indexOf('#');
				String owner = remap.substring(0, split);
				remap = remap.substring(split + 1);

				split = remap.indexOf('(');
				String name = remap.substring(0, split);
				String desc = remap.substring(split);

				idin.bsmArgs[1] = new Handle(handle.getTag(), owner, name, desc, handle.isInterface());

				if (!desc.equals(handle.getDesc())) {//Shouldn't ever do this, the methods aren't really equal if the descriptions are different
					throw new IllegalStateException("Description changed remapping lambda handle: " + handle + " => " + idin.bsmArgs[1]);
				}
			}
		}));
		changes.refreshChanges(originalMethods);
	}

	private static void rebuildOrder(ClassNode node, byte[] basis) {
		if (basis == null) return; //Nothing to rebuild from

		ClassNode basisNode = new ClassNode();
		new ClassReader(basis).accept(basisNode, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);

		fix(node.name, "inner class", node.innerClasses, basisNode.innerClasses, innerClass -> innerClass.name);
		fix(node.name, "method", node.methods, basisNode.methods, method -> method.name + method.desc);
	}

	private static class Swap {
		public final int a, b;

		public Swap(int a, int b) {
			this.a = a;
			this.b = b;
		}

		@Override
		public String toString() {
			return "Swap[" + a + " <=> " + b + ']';
		}
	}

	private static <T> void fix(String name, String type, List<T> client, List<T> server, Function<T, String> mapper) {
		List<Swap> swaps = fix(name, type, client.stream().map(mapper).collect(Collectors.toList()), server.stream().map(mapper).collect(Collectors.toList()));

		for (Swap swap : swaps) {
			Collections.swap(client, swap.a, swap.b);
		}

		if (!swaps.isEmpty()) System.out.println("Resolved " + type + " issues in " + name);
	}

	private static List<Swap> fix(String name, String type, List<String> client, List<String> server) {
		List<Swap> swaps = new ArrayList<>();

		boolean attemptedFix = false;
		for (int c = 0, s = 0; c < client.size() && s < server.size();) {//StitchUtil#mergePreserveOrder can deadlock under certain orderings, let's try not let it
			boolean madeShift = false;

			while (c < client.size() && s < server.size() && client.get(c).equals(server.get(s))) {
                c++;
                s++;
                madeShift = true;
            }

            while (c < client.size() && !server.contains(client.get(c))) {
                c++;
                madeShift = true;
            }

            while (s < server.size() && !client.contains(server.get(s))) {
                s++;
                madeShift = true;
            }

            if (!madeShift) {//Will deadlock in Stitch
            	if (attemptedFix) {
            		throw new IllegalStateException("Unable to fix " + type + " inconsistencies in " + name);
            	} else {
            		System.out.println(Character.toUpperCase(type.charAt(0)) + type.substring(1) + " deadlock found in " + name + " at " + c + ", " + s);
            		Collections.swap(client, c, c + 1);
            		swaps.add(new Swap(c, c + 1));
            		attemptedFix = true;
            	}
            } else if (attemptedFix) {//Fixed the problem
            	attemptedFix = false;
            }
		}

		return swaps;
	}

	private static byte[] write(ClassNode node) {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | 0/*ClassWriter.COMPUTE_FRAMES*/);
		node.accept(writer);
		return writer.toByteArray();
	}
}
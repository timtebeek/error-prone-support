package tech.picnic.errorprone.refasterrules;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.MoreCollectors.toOptional;
import static com.google.errorprone.BugPattern.SeverityLevel.WARNING;
import static com.google.errorprone.refaster.ImportPolicy.STATIC_IMPORT_ALWAYS;
import static java.util.Comparator.naturalOrder;
import static java.util.Comparator.reverseOrder;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.maxBy;
import static java.util.stream.Collectors.minBy;
import static java.util.stream.Collectors.toCollection;
import static org.assertj.core.api.Assertions.assertThat;
import static reactor.function.TupleUtils.function;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.refaster.Refaster;
import com.google.errorprone.refaster.annotation.AfterTemplate;
import com.google.errorprone.refaster.annotation.BeforeTemplate;
import com.google.errorprone.refaster.annotation.Matches;
import com.google.errorprone.refaster.annotation.MayOptionallyUse;
import com.google.errorprone.refaster.annotation.NotMatches;
import com.google.errorprone.refaster.annotation.Placeholder;
import com.google.errorprone.refaster.annotation.UseImportPolicy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collector;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.math.MathFlux;
import reactor.test.StepVerifier;
import reactor.test.publisher.PublisherProbe;
import reactor.util.context.Context;
import reactor.util.function.Tuple2;
import tech.picnic.errorprone.refaster.annotation.Description;
import tech.picnic.errorprone.refaster.annotation.OnlineDocumentation;
import tech.picnic.errorprone.refaster.annotation.Severity;
import tech.picnic.errorprone.refaster.matchers.IsEmpty;
import tech.picnic.errorprone.refaster.matchers.IsIdentityOperation;
import tech.picnic.errorprone.refaster.matchers.ThrowsCheckedException;

/** Refaster rules related to Reactor expressions and statements. */
@OnlineDocumentation
final class ReactorRules {
  private ReactorRules() {}

  /**
   * Prefer {@link Mono#fromSupplier(Supplier)} over {@link Mono#fromCallable(Callable)} where
   * feasible.
   */
  static final class MonoFromSupplier<T> {
    @BeforeTemplate
    Mono<T> before(@NotMatches(ThrowsCheckedException.class) Callable<? extends T> supplier) {
      return Mono.fromCallable(supplier);
    }

    @AfterTemplate
    Mono<T> after(Supplier<? extends T> supplier) {
      return Mono.fromSupplier(supplier);
    }
  }

  /** Prefer {@link Mono#empty()} over more contrived alternatives. */
  static final class MonoEmpty<T> {
    @BeforeTemplate
    Mono<T> before() {
      return Refaster.anyOf(Mono.justOrEmpty(null), Mono.justOrEmpty(Optional.empty()));
    }

    @AfterTemplate
    Mono<T> after() {
      return Mono.empty();
    }
  }

  /** Prefer {@link Mono#just(Object)} over more contrived alternatives. */
  static final class MonoJust<T> {
    @BeforeTemplate
    Mono<T> before(T value) {
      return Mono.justOrEmpty(Optional.of(value));
    }

    @AfterTemplate
    Mono<T> after(T value) {
      return Mono.just(value);
    }
  }

  /** Prefer {@link Mono#justOrEmpty(Object)} over more contrived alternatives. */
  static final class MonoJustOrEmptyObject<T extends @Nullable Object> {
    @BeforeTemplate
    Mono<T> before(T value) {
      return Mono.justOrEmpty(Optional.ofNullable(value));
    }

    @AfterTemplate
    Mono<T> after(T value) {
      return Mono.justOrEmpty(value);
    }
  }

  /** Prefer {@link Mono#justOrEmpty(Optional)} over more verbose alternatives. */
  static final class MonoJustOrEmptyOptional<T> {
    @BeforeTemplate
    Mono<T> before(Optional<T> optional) {
      return Mono.just(optional).filter(Optional::isPresent).map(Optional::orElseThrow);
    }

    @AfterTemplate
    Mono<T> after(Optional<T> optional) {
      return Mono.justOrEmpty(optional);
    }
  }

  /**
   * Prefer {@link Mono#defer(Supplier) deferring} {@link Mono#justOrEmpty(Optional)} over more
   * verbose alternatives.
   */
  // XXX: If `optional` is a constant and effectively-final expression then the `Mono.defer` can be
  // dropped. Should look into Refaster support for identifying this.
  static final class MonoDeferMonoJustOrEmpty<T> {
    @BeforeTemplate
    @SuppressWarnings(
        "MonoFromSupplier" /* `optional` may match a checked exception-throwing expression. */)
    Mono<T> before(Optional<T> optional) {
      return Refaster.anyOf(
          Mono.fromCallable(() -> optional.orElse(null)),
          Mono.fromSupplier(() -> optional.orElse(null)));
    }

    @AfterTemplate
    Mono<T> after(Optional<T> optional) {
      return Mono.defer(() -> Mono.justOrEmpty(optional));
    }
  }

  /**
   * Try to avoid expressions of type {@code Optional<Mono<T>>}, but if you must map an {@link
   * Optional} to this type, prefer using {@link Mono#just(Object)}.
   */
  static final class OptionalMapMonoJust<T> {
    @BeforeTemplate
    Optional<Mono<T>> before(Optional<T> optional) {
      return optional.map(Mono::justOrEmpty);
    }

    @AfterTemplate
    Optional<Mono<T>> after(Optional<T> optional) {
      return optional.map(Mono::just);
    }
  }

  /**
   * Prefer a {@link Mono#justOrEmpty(Optional)} and {@link Mono#switchIfEmpty(Mono)} chain over
   * more contrived alternatives.
   *
   * <p>In particular, avoid mixing of the {@link Optional} and {@link Mono} APIs.
   */
  static final class MonoFromOptionalSwitchIfEmpty<T> {
    @BeforeTemplate
    Mono<T> before(Optional<T> optional, Mono<T> mono) {
      return optional.map(Mono::just).orElse(mono);
    }

    @AfterTemplate
    Mono<T> after(Optional<T> optional, Mono<T> mono) {
      return Mono.justOrEmpty(optional).switchIfEmpty(mono);
    }
  }

  /**
   * Prefer {@link Mono#zip(Mono, Mono)} over a chained {@link Mono#zipWith(Mono)}, as the former
   * better conveys that the {@link Mono}s may be subscribed to concurrently, and generalizes to
   * combining three or more reactive streams.
   */
  static final class MonoZip<T, S> {
    @BeforeTemplate
    Mono<Tuple2<T, S>> before(Mono<T> mono, Mono<S> other) {
      return mono.zipWith(other);
    }

    @AfterTemplate
    Mono<Tuple2<T, S>> after(Mono<T> mono, Mono<S> other) {
      return Mono.zip(mono, other);
    }
  }

  /**
   * Prefer {@link Mono#zip(Mono, Mono)} with a chained combinator over a chained {@link
   * Mono#zipWith(Mono, BiFunction)}, as the former better conveys that the {@link Mono}s may be
   * subscribed to concurrently, and generalizes to combining three or more reactive streams.
   */
  static final class MonoZipWithCombinator<T, S, R> {
    @BeforeTemplate
    Mono<R> before(Mono<T> mono, Mono<S> other, BiFunction<T, S, R> combinator) {
      return mono.zipWith(other, combinator);
    }

    @AfterTemplate
    Mono<R> after(Mono<T> mono, Mono<S> other, BiFunction<T, S, R> combinator) {
      return Mono.zip(mono, other).map(function(combinator));
    }
  }

  /**
   * Prefer {@link Flux#zip(Publisher, Publisher)} over a chained {@link Flux#zipWith(Publisher)},
   * as the former better conveys that the {@link Publisher}s may be subscribed to concurrently, and
   * generalizes to combining three or more reactive streams.
   */
  static final class FluxZip<T, S> {
    @BeforeTemplate
    Flux<Tuple2<T, S>> before(Flux<T> flux, Publisher<S> other) {
      return flux.zipWith(other);
    }

    @AfterTemplate
    Flux<Tuple2<T, S>> after(Flux<T> flux, Publisher<S> other) {
      return Flux.zip(flux, other);
    }
  }

  /**
   * Prefer {@link Flux#zip(Publisher, Publisher)} with a chained combinator over a chained {@link
   * Flux#zipWith(Publisher, BiFunction)}, as the former better conveys that the {@link Publisher}s
   * may be subscribed to concurrently, and generalizes to combining three or more reactive streams.
   */
  static final class FluxZipWithCombinator<T, S, R> {
    @BeforeTemplate
    Flux<R> before(Flux<T> flux, Publisher<S> other, BiFunction<T, S, R> combinator) {
      return flux.zipWith(other, combinator);
    }

    @AfterTemplate
    Flux<R> after(Flux<T> flux, Publisher<S> other, BiFunction<T, S, R> combinator) {
      return Flux.zip(flux, other).map(function(combinator));
    }
  }

  /** Prefer {@link Flux#zipWithIterable(Iterable)} over more contrived alternatives. */
  static final class FluxZipWithIterable<T, S> {
    @BeforeTemplate
    Flux<Tuple2<T, S>> before(Flux<T> flux, Iterable<S> iterable) {
      return Flux.zip(flux, Flux.fromIterable(iterable));
    }

    @AfterTemplate
    Flux<Tuple2<T, S>> after(Flux<T> flux, Iterable<S> iterable) {
      return flux.zipWithIterable(iterable);
    }
  }

  /** Prefer {@link Flux#zipWithIterable(Iterable, BiFunction)} over more contrived alternatives. */
  static final class FluxZipWithIterableBiFunction<T, S, R> {
    @BeforeTemplate
    Flux<R> before(
        Flux<T> flux,
        Iterable<S> iterable,
        BiFunction<? super T, ? super S, ? extends R> function) {
      return flux.zipWith(Flux.fromIterable(iterable), function);
    }

    @AfterTemplate
    Flux<R> after(
        Flux<T> flux,
        Iterable<S> iterable,
        BiFunction<? super T, ? super S, ? extends R> function) {
      return flux.zipWithIterable(iterable, function);
    }
  }

  /**
   * Prefer {@link Flux#zipWithIterable(Iterable)} with a chained combinator over {@link
   * Flux#zipWithIterable(Iterable, BiFunction)}, as the former generally yields more readable code.
   */
  static final class FluxZipWithIterableMapFunction<T, S, R> {
    @BeforeTemplate
    Flux<R> before(Flux<T> flux, Iterable<S> iterable, BiFunction<T, S, R> combinator) {
      return flux.zipWithIterable(iterable, combinator);
    }

    @AfterTemplate
    @UseImportPolicy(STATIC_IMPORT_ALWAYS)
    Flux<R> after(Flux<T> flux, Iterable<S> iterable, BiFunction<T, S, R> combinator) {
      return flux.zipWithIterable(iterable).map(function(combinator));
    }
  }

  /** Don't unnecessarily defer {@link Mono#error(Throwable)}. */
  static final class MonoDeferredError<T> {
    @BeforeTemplate
    Mono<T> before(Throwable throwable) {
      return Mono.defer(() -> Mono.error(throwable));
    }

    @AfterTemplate
    Mono<T> after(Throwable throwable) {
      return Mono.error(() -> throwable);
    }
  }

  /** Don't unnecessarily defer {@link Flux#error(Throwable)}. */
  static final class FluxDeferredError<T> {
    @BeforeTemplate
    Flux<T> before(Throwable throwable) {
      return Flux.defer(() -> Flux.error(throwable));
    }

    @AfterTemplate
    Flux<T> after(Throwable throwable) {
      return Flux.error(() -> throwable);
    }
  }

  /**
   * Don't unnecessarily pass {@link Mono#error(Supplier)} a method reference or lambda expression.
   */
  // XXX: Drop this rule once the more general rule `AssortedRules#SupplierAsSupplier` works
  // reliably.
  static final class MonoErrorSupplier<T, E extends Throwable> {
    @BeforeTemplate
    Mono<T> before(Supplier<E> supplier) {
      return Mono.error(() -> supplier.get());
    }

    @AfterTemplate
    Mono<T> after(Supplier<E> supplier) {
      return Mono.error(supplier);
    }
  }

  /**
   * Don't unnecessarily pass {@link Flux#error(Supplier)} a method reference or lambda expression.
   */
  // XXX: Drop this rule once the more general rule `AssortedRules#SupplierAsSupplier` works
  // reliably.
  static final class FluxErrorSupplier<T, E extends Throwable> {
    @BeforeTemplate
    Flux<T> before(Supplier<E> supplier) {
      return Flux.error(() -> supplier.get());
    }

    @AfterTemplate
    Flux<T> after(Supplier<E> supplier) {
      return Flux.error(supplier);
    }
  }

  /** Prefer {@link Mono#thenReturn(Object)} over more verbose alternatives. */
  static final class MonoThenReturn<T, S> {
    @BeforeTemplate
    Mono<S> before(Mono<T> mono, S object) {
      return Refaster.anyOf(
          mono.ignoreElement().thenReturn(object),
          mono.then().thenReturn(object),
          mono.then(Mono.just(object)));
    }

    @AfterTemplate
    Mono<S> after(Mono<T> mono, S object) {
      return mono.thenReturn(object);
    }
  }

  /**
   * Prefer {@link Flux#take(long, boolean)} over {@link Flux#take(long)}.
   *
   * <p>In Reactor versions prior to 3.5.0, {@code Flux#take(long)} makes an unbounded request
   * upstream, and is equivalent to {@code Flux#take(long, false)}. In 3.5.0, the behavior of {@code
   * Flux#take(long)} will change to that of {@code Flux#take(long, true)}.
   *
   * <p>The intent with this Refaster rule is to get the new behavior before upgrading to Reactor
   * 3.5.0.
   */
  // XXX: Drop this rule some time after upgrading to Reactor 3.6.0, or introduce a way to apply
  // this rule only when an older version of Reactor is on the classpath.
  // XXX: Once Reactor 3.6.0 is out, introduce a rule that rewrites code in the opposite direction.
  @Description(
      "Prior to Reactor 3.5.0, `take(n)` requests and unbounded number of elements upstream.")
  @Severity(WARNING)
  static final class FluxTake<T> {
    @BeforeTemplate
    Flux<T> before(Flux<T> flux, long n) {
      return flux.take(n);
    }

    @AfterTemplate
    Flux<T> after(Flux<T> flux, long n) {
      return flux.take(n, /* limitRequest= */ true);
    }
  }

  /** Prefer {@link Mono#defaultIfEmpty(Object)} over more contrived alternatives. */
  static final class MonoDefaultIfEmpty<T> {
    @BeforeTemplate
    Mono<T> before(Mono<T> mono, T object) {
      return mono.switchIfEmpty(Mono.just(object));
    }

    @AfterTemplate
    Mono<T> after(Mono<T> mono, T object) {
      return mono.defaultIfEmpty(object);
    }
  }

  /** Prefer {@link Flux#defaultIfEmpty(Object)} over more contrived alternatives. */
  static final class FluxDefaultIfEmpty<T> {
    @BeforeTemplate
    Flux<T> before(Flux<T> flux, T object) {
      return flux.switchIfEmpty(Refaster.anyOf(Mono.just(object), Flux.just(object)));
    }

    @AfterTemplate
    Flux<T> after(Flux<T> flux, T object) {
      return flux.defaultIfEmpty(object);
    }
  }

  /** Prefer {@link Flux#empty()} over more contrived alternatives. */
  // XXX: In combination with the `IsEmpty` matcher introduced by
  // https://github.com/PicnicSupermarket/error-prone-support/pull/744, the non-varargs overloads of
  // most methods referenced here can be rewritten as well. Additionally, some invocations of
  // methods such as `Flux#fromIterable`, `Flux#fromArray` and `Flux#justOrEmpty` can also be
  // rewritten.
  static final class FluxEmpty<T, S extends Comparable<? super S>> {
    @BeforeTemplate
    Flux<T> before(
        Function<? super Object[], ? extends T> combinator,
        int prefetch,
        Comparator<? super T> comparator) {
      return Refaster.anyOf(
          Flux.zip(combinator),
          Flux.zip(combinator, prefetch),
          Flux.concat(),
          Flux.concatDelayError(),
          Flux.firstWithSignal(),
          Flux.just(),
          Flux.merge(),
          Flux.merge(prefetch),
          Flux.mergeComparing(comparator),
          Flux.mergeComparing(prefetch, comparator),
          Flux.mergeComparingDelayError(prefetch, comparator),
          Flux.mergeDelayError(prefetch),
          Flux.mergePriority(comparator),
          Flux.mergePriority(prefetch, comparator),
          Flux.mergePriorityDelayError(prefetch, comparator),
          Flux.mergeSequential(),
          Flux.mergeSequential(prefetch),
          Flux.mergeSequentialDelayError(prefetch));
    }

    @BeforeTemplate
    Flux<T> before(Function<Object[], T> combinator, int prefetch) {
      return Refaster.anyOf(
          Flux.combineLatest(combinator), Flux.combineLatest(combinator, prefetch));
    }

    @BeforeTemplate
    Flux<S> before() {
      return Refaster.anyOf(Flux.mergeComparing(), Flux.mergePriority());
    }

    @BeforeTemplate
    Flux<Integer> before(int start) {
      return Flux.range(start, 0);
    }

    @AfterTemplate
    Flux<T> after() {
      return Flux.empty();
    }
  }

  /** Prefer {@link Flux#just(Object)} over more contrived alternatives. */
  static final class FluxJust {
    @BeforeTemplate
    Flux<Integer> before(int start) {
      return Flux.range(start, 1);
    }

    @AfterTemplate
    Flux<Integer> after(int start) {
      return Flux.just(start);
    }
  }

  /** Don't unnecessarily transform a {@link Mono} to an equivalent instance. */
  static final class MonoIdentity<T> {
    @BeforeTemplate
    Mono<T> before(Mono<T> mono) {
      return Refaster.anyOf(
          mono.switchIfEmpty(Mono.empty()), mono.flux().next(), mono.flux().singleOrEmpty());
    }

    @BeforeTemplate
    Mono<@Nullable Void> before2(Mono<@Nullable Void> mono) {
      return Refaster.anyOf(mono.ignoreElement(), mono.then());
    }

    // XXX: Replace this rule with an extension of the `IdentityConversion` rule, supporting
    // `Stream#map`, `Mono#map` and `Flux#map`. Alternatively, extend the `IsIdentityOperation`
    // matcher and use it to constrain the matched `map` argument.
    @BeforeTemplate
    Mono<ImmutableList<T>> before3(Mono<ImmutableList<T>> mono) {
      return mono.map(ImmutableList::copyOf);
    }

    @AfterTemplate
    @CanIgnoreReturnValue
    Mono<T> after(Mono<T> mono) {
      return mono;
    }
  }

  /** Don't unnecessarily transform a {@link Mono} to a {@link Flux} to expect exactly one item. */
  static final class MonoSingle<T> {
    @BeforeTemplate
    Mono<T> before(Mono<T> mono) {
      return mono.flux().single();
    }

    @AfterTemplate
    Mono<T> after(Mono<T> mono) {
      return mono.single();
    }
  }

  /** Don't unnecessarily pass an empty publisher to {@link Flux#switchIfEmpty(Publisher)}. */
  static final class FluxSwitchIfEmptyOfEmptyPublisher<T> {
    @BeforeTemplate
    Flux<T> before(Flux<T> flux) {
      return flux.switchIfEmpty(Refaster.anyOf(Mono.empty(), Flux.empty()));
    }

    @AfterTemplate
    @CanIgnoreReturnValue
    Flux<T> after(Flux<T> flux) {
      return flux;
    }
  }

  /** Prefer {@link Flux#concatMap(Function)} over more contrived alternatives. */
  static final class FluxConcatMap<T, S, P extends Publisher<? extends S>> {
    @BeforeTemplate
    @SuppressWarnings("NestedPublishers")
    Flux<S> before(
        Flux<T> flux,
        Function<? super T, ? extends P> function,
        @Matches(IsIdentityOperation.class)
            Function<? super P, ? extends Publisher<? extends S>> identityOperation) {
      return Refaster.anyOf(
          flux.flatMap(function, 1),
          flux.flatMapSequential(function, 1),
          flux.map(function).concatMap(identityOperation));
    }

    @AfterTemplate
    Flux<S> after(Flux<T> flux, Function<? super T, ? extends P> function) {
      return flux.concatMap(function);
    }
  }

  /** Prefer {@link Flux#concatMap(Function, int)} over more contrived alternatives. */
  static final class FluxConcatMapWithPrefetch<T, S, P extends Publisher<? extends S>> {
    @BeforeTemplate
    @SuppressWarnings("NestedPublishers")
    Flux<S> before(
        Flux<T> flux,
        Function<? super T, ? extends P> function,
        int prefetch,
        @Matches(IsIdentityOperation.class)
            Function<? super P, ? extends Publisher<? extends S>> identityOperation) {
      return Refaster.anyOf(
          flux.flatMap(function, 1, prefetch),
          flux.flatMapSequential(function, 1, prefetch),
          flux.map(function).concatMap(identityOperation, prefetch));
    }

    @AfterTemplate
    Flux<S> after(Flux<T> flux, Function<? super T, ? extends P> function, int prefetch) {
      return flux.concatMap(function, prefetch);
    }
  }

  /** Avoid contrived alternatives to {@link Mono#flatMapIterable(Function)}. */
  static final class MonoFlatMapIterable<T, S, I extends Iterable<? extends S>> {
    @BeforeTemplate
    Flux<S> before(Mono<T> mono, Function<? super T, I> function) {
      return mono.map(function).flatMapMany(Flux::fromIterable);
    }

    @BeforeTemplate
    Flux<S> before(
        Mono<T> mono,
        Function<? super T, I> function,
        @Matches(IsIdentityOperation.class)
            Function<? super I, ? extends Iterable<? extends S>> identityOperation) {
      return Refaster.anyOf(
          mono.map(function).flatMapIterable(identityOperation),
          mono.flux().concatMapIterable(function));
    }

    @AfterTemplate
    Flux<S> after(Mono<T> mono, Function<? super T, I> function) {
      return mono.flatMapIterable(function);
    }
  }

  /**
   * Prefer {@link Mono#flatMapIterable(Function)} to flatten a {@link Mono} of some {@link
   * Iterable} over less efficient alternatives.
   */
  static final class MonoFlatMapIterableIdentity<T, S extends Iterable<T>> {
    @BeforeTemplate
    Flux<T> before(Mono<S> mono) {
      return mono.flatMapMany(Flux::fromIterable);
    }

    @AfterTemplate
    @UseImportPolicy(STATIC_IMPORT_ALWAYS)
    Flux<T> after(Mono<S> mono) {
      return mono.flatMapIterable(identity());
    }
  }

  /**
   * Prefer {@link Flux#concatMapIterable(Function)} over alternatives with less clear syntax or
   * semantics.
   */
  static final class FluxConcatMapIterable<T, S, I extends Iterable<? extends S>> {
    @BeforeTemplate
    Flux<S> before(
        Flux<T> flux,
        Function<? super T, I> function,
        @Matches(IsIdentityOperation.class)
            Function<? super I, ? extends Iterable<? extends S>> identityOperation) {
      return Refaster.anyOf(
          flux.flatMapIterable(function), flux.map(function).concatMapIterable(identityOperation));
    }

    @AfterTemplate
    Flux<S> after(Flux<T> flux, Function<? super T, ? extends Iterable<? extends S>> function) {
      return flux.concatMapIterable(function);
    }
  }

  /**
   * Prefer {@link Flux#concatMapIterable(Function, int)} over alternatives with less clear syntax
   * or semantics.
   */
  static final class FluxConcatMapIterableWithPrefetch<T, S, I extends Iterable<? extends S>> {
    @BeforeTemplate
    Flux<S> before(
        Flux<T> flux,
        Function<? super T, I> function,
        int prefetch,
        @Matches(IsIdentityOperation.class)
            Function<? super I, ? extends Iterable<? extends S>> identityOperation) {
      return Refaster.anyOf(
          flux.flatMapIterable(function, prefetch),
          flux.map(function).concatMapIterable(identityOperation, prefetch));
    }

    @AfterTemplate
    Flux<S> after(
        Flux<T> flux, Function<? super T, ? extends Iterable<? extends S>> function, int prefetch) {
      return flux.concatMapIterable(function, prefetch);
    }
  }

  /**
   * Don't use {@link Mono#flatMapMany(Function)} to implicitly convert a {@link Mono} to a {@link
   * Flux}.
   */
  abstract static class MonoFlatMapToFlux<T, S> {
    // XXX: It would be more expressive if this `@Placeholder` were replaced with a `Function<?
    // super T, ? extends Mono<? extends S>>` parameter, so that compatible non-lambda expression
    // arguments to `flatMapMany` are also matched. However, the type inferred for lambda and method
    // reference expressions passed to `flatMapMany` appears to always be `Function<T, Publisher<?
    // extends S>>`, which doesn't match. Find a solution.
    @Placeholder(allowsIdentity = true)
    abstract Mono<S> transformation(@MayOptionallyUse T value);

    @BeforeTemplate
    Flux<S> before(Mono<T> mono) {
      return mono.flatMapMany(v -> transformation(v));
    }

    @AfterTemplate
    Flux<S> after(Mono<T> mono) {
      return mono.flatMap(v -> transformation(v)).flux();
    }
  }

  /**
   * Prefer {@link Mono#map(Function)} over alternatives that unnecessarily require an inner
   * subscription.
   */
  abstract static class MonoMap<T, S> {
    @Placeholder(allowsIdentity = true)
    abstract S transformation(@MayOptionallyUse T value);

    @BeforeTemplate
    Mono<S> before(Mono<T> mono) {
      return mono.flatMap(x -> Mono.just(transformation(x)));
    }

    @AfterTemplate
    Mono<S> after(Mono<T> mono) {
      return mono.map(x -> transformation(x));
    }
  }

  /**
   * Prefer {@link Flux#map(Function)} over alternatives that unnecessarily require an inner
   * subscription.
   */
  abstract static class FluxMap<T, S> {
    @Placeholder(allowsIdentity = true)
    abstract S transformation(@MayOptionallyUse T value);

    @BeforeTemplate
    Flux<S> before(Flux<T> flux, int prefetch, boolean delayUntilEnd, int maxConcurrency) {
      return Refaster.anyOf(
          flux.concatMap(x -> Mono.just(transformation(x))),
          flux.concatMap(x -> Flux.just(transformation(x))),
          flux.concatMap(x -> Mono.just(transformation(x)), prefetch),
          flux.concatMap(x -> Flux.just(transformation(x)), prefetch),
          flux.concatMapDelayError(x -> Mono.just(transformation(x))),
          flux.concatMapDelayError(x -> Flux.just(transformation(x))),
          flux.concatMapDelayError(x -> Mono.just(transformation(x)), prefetch),
          flux.concatMapDelayError(x -> Flux.just(transformation(x)), prefetch),
          flux.concatMapDelayError(x -> Mono.just(transformation(x)), delayUntilEnd, prefetch),
          flux.concatMapDelayError(x -> Flux.just(transformation(x)), delayUntilEnd, prefetch),
          flux.flatMap(x -> Mono.just(transformation(x)), maxConcurrency),
          flux.flatMap(x -> Flux.just(transformation(x)), maxConcurrency),
          flux.flatMap(x -> Mono.just(transformation(x)), maxConcurrency, prefetch),
          flux.flatMap(x -> Flux.just(transformation(x)), maxConcurrency, prefetch),
          flux.flatMapDelayError(x -> Mono.just(transformation(x)), maxConcurrency, prefetch),
          flux.flatMapDelayError(x -> Flux.just(transformation(x)), maxConcurrency, prefetch),
          flux.flatMapSequential(x -> Mono.just(transformation(x)), maxConcurrency),
          flux.flatMapSequential(x -> Flux.just(transformation(x)), maxConcurrency),
          flux.flatMapSequential(x -> Mono.just(transformation(x)), maxConcurrency, prefetch),
          flux.flatMapSequential(x -> Flux.just(transformation(x)), maxConcurrency, prefetch),
          flux.flatMapSequentialDelayError(
              x -> Mono.just(transformation(x)), maxConcurrency, prefetch),
          flux.flatMapSequentialDelayError(
              x -> Flux.just(transformation(x)), maxConcurrency, prefetch),
          flux.switchMap(x -> Mono.just(transformation(x))),
          flux.switchMap(x -> Flux.just(transformation(x))));
    }

    @AfterTemplate
    Flux<S> after(Flux<T> flux) {
      return flux.map(x -> transformation(x));
    }
  }

  /**
   * Prefer {@link Mono#mapNotNull(Function)} over alternatives that unnecessarily require an inner
   * subscription.
   */
  abstract static class MonoMapNotNull<T, S> {
    @Placeholder(allowsIdentity = true)
    abstract S transformation(@MayOptionallyUse T value);

    @BeforeTemplate
    Mono<S> before(Mono<T> mono) {
      return mono.flatMap(
          x ->
              Refaster.anyOf(
                  Mono.justOrEmpty(transformation(x)), Mono.fromSupplier(() -> transformation(x))));
    }

    @AfterTemplate
    Mono<S> after(Mono<T> mono) {
      return mono.mapNotNull(x -> transformation(x));
    }
  }

  /**
   * Prefer {@link Flux#mapNotNull(Function)} over alternatives that unnecessarily require an inner
   * subscription.
   */
  abstract static class FluxMapNotNull<T, S> {
    @Placeholder(allowsIdentity = true)
    abstract S transformation(@MayOptionallyUse T value);

    @BeforeTemplate
    @SuppressWarnings("java:S138" /* Method is long, but not complex. */)
    Publisher<S> before(Flux<T> flux, int prefetch, boolean delayUntilEnd, int maxConcurrency) {
      return Refaster.anyOf(
          flux.concatMap(
              x ->
                  Refaster.anyOf(
                      Mono.justOrEmpty(transformation(x)),
                      Mono.fromSupplier(() -> transformation(x)))),
          flux.concatMap(
              x ->
                  Refaster.anyOf(
                      Mono.justOrEmpty(transformation(x)),
                      Mono.fromSupplier(() -> transformation(x))),
              prefetch),
          flux.concatMapDelayError(
              x ->
                  Refaster.anyOf(
                      Mono.justOrEmpty(transformation(x)),
                      Mono.fromSupplier(() -> transformation(x)))),
          flux.concatMapDelayError(
              x ->
                  Refaster.anyOf(
                      Mono.justOrEmpty(transformation(x)),
                      Mono.fromSupplier(() -> transformation(x))),
              prefetch),
          flux.concatMapDelayError(
              x ->
                  Refaster.anyOf(
                      Mono.justOrEmpty(transformation(x)),
                      Mono.fromSupplier(() -> transformation(x))),
              delayUntilEnd,
              prefetch),
          flux.flatMap(
              x ->
                  Refaster.anyOf(
                      Mono.justOrEmpty(transformation(x)),
                      Mono.fromSupplier(() -> transformation(x))),
              maxConcurrency),
          flux.flatMap(
              x ->
                  Refaster.anyOf(
                      Mono.justOrEmpty(transformation(x)),
                      Mono.fromSupplier(() -> transformation(x))),
              maxConcurrency,
              prefetch),
          flux.flatMapDelayError(
              x ->
                  Refaster.anyOf(
                      Mono.justOrEmpty(transformation(x)),
                      Mono.fromSupplier(() -> transformation(x))),
              maxConcurrency,
              prefetch),
          flux.flatMapSequential(
              x ->
                  Refaster.anyOf(
                      Mono.justOrEmpty(transformation(x)),
                      Mono.fromSupplier(() -> transformation(x))),
              maxConcurrency),
          flux.flatMapSequential(
              x ->
                  Refaster.anyOf(
                      Mono.justOrEmpty(transformation(x)),
                      Mono.fromSupplier(() -> transformation(x))),
              maxConcurrency,
              prefetch),
          flux.flatMapSequentialDelayError(
              x ->
                  Refaster.anyOf(
                      Mono.justOrEmpty(transformation(x)),
                      Mono.fromSupplier(() -> transformation(x))),
              maxConcurrency,
              prefetch),
          flux.switchMap(
              x ->
                  Refaster.anyOf(
                      Mono.justOrEmpty(transformation(x)),
                      Mono.fromSupplier(() -> transformation(x)))));
    }

    @AfterTemplate
    Flux<S> after(Flux<T> flux) {
      return flux.mapNotNull(x -> transformation(x));
    }
  }

  /** Prefer {@link Mono#flux()}} over more contrived alternatives. */
  static final class MonoFlux<T> {
    @BeforeTemplate
    Flux<T> before(Mono<T> mono) {
      return Refaster.anyOf(
          mono.flatMapMany(Mono::just), mono.flatMapMany(Flux::just), Flux.concat(mono));
    }

    @AfterTemplate
    Flux<T> after(Mono<T> mono) {
      return mono.flux();
    }
  }

  /** Prefer direct invocation of {@link Mono#then()}} over more contrived alternatives. */
  static final class MonoThen<T> {
    @BeforeTemplate
    Mono<@Nullable Void> before(Mono<T> mono) {
      return Refaster.anyOf(mono.ignoreElement().then(), mono.flux().then());
    }

    @AfterTemplate
    Mono<@Nullable Void> after(Mono<T> mono) {
      return mono.then();
    }
  }

  /** Avoid vacuous invocations of {@link Flux#ignoreElements()}. */
  static final class FluxThen<T> {
    @BeforeTemplate
    Mono<@Nullable Void> before(Flux<T> flux) {
      return flux.ignoreElements().then();
    }

    @BeforeTemplate
    Mono<@Nullable Void> before2(Flux<@Nullable Void> flux) {
      return flux.ignoreElements();
    }

    @AfterTemplate
    Mono<@Nullable Void> after(Flux<T> flux) {
      return flux.then();
    }
  }

  /** Avoid vacuous invocations of {@link Mono#ignoreElement()}. */
  static final class MonoThenEmpty<T> {
    @BeforeTemplate
    Mono<@Nullable Void> before(Mono<T> mono, Publisher<@Nullable Void> publisher) {
      return mono.ignoreElement().thenEmpty(publisher);
    }

    @AfterTemplate
    Mono<@Nullable Void> after(Mono<T> mono, Publisher<@Nullable Void> publisher) {
      return mono.thenEmpty(publisher);
    }
  }

  /** Avoid vacuous invocations of {@link Flux#ignoreElements()}. */
  static final class FluxThenEmpty<T> {
    @BeforeTemplate
    Mono<@Nullable Void> before(Flux<T> flux, Publisher<@Nullable Void> publisher) {
      return flux.ignoreElements().thenEmpty(publisher);
    }

    @AfterTemplate
    Mono<@Nullable Void> after(Flux<T> flux, Publisher<@Nullable Void> publisher) {
      return flux.thenEmpty(publisher);
    }
  }

  /** Avoid vacuous operations prior to invocation of {@link Mono#thenMany(Publisher)}. */
  static final class MonoThenMany<T, S> {
    @BeforeTemplate
    Flux<S> before(Mono<T> mono, Publisher<S> publisher) {
      return Refaster.anyOf(
          mono.ignoreElement().thenMany(publisher), mono.flux().thenMany(publisher));
    }

    @AfterTemplate
    Flux<S> after(Mono<T> mono, Publisher<S> publisher) {
      return mono.thenMany(publisher);
    }
  }

  /**
   * Prefer explicit invocation of {@link Mono#flux()} over implicit conversions from {@link Mono}
   * to {@link Flux}.
   */
  static final class MonoThenMonoFlux<T, S> {
    @BeforeTemplate
    Flux<S> before(Mono<T> mono1, Mono<S> mono2) {
      return mono1.thenMany(mono2);
    }

    @AfterTemplate
    Flux<S> after(Mono<T> mono1, Mono<S> mono2) {
      return mono1.then(mono2).flux();
    }
  }

  /** Avoid vacuous invocations of {@link Flux#ignoreElements()}. */
  static final class FluxThenMany<T, S> {
    @BeforeTemplate
    Flux<S> before(Flux<T> flux, Publisher<S> publisher) {
      return flux.ignoreElements().thenMany(publisher);
    }

    @AfterTemplate
    Flux<S> after(Flux<T> flux, Publisher<S> publisher) {
      return flux.thenMany(publisher);
    }
  }

  /** Avoid vacuous operations prior to invocation of {@link Mono#then(Mono)}. */
  static final class MonoThenMono<T, S> {
    @BeforeTemplate
    Mono<S> before(Mono<T> mono1, Mono<S> mono2) {
      return Refaster.anyOf(mono1.ignoreElement().then(mono2), mono1.flux().then(mono2));
    }

    @BeforeTemplate
    Mono<@Nullable Void> before2(Mono<T> mono1, Mono<@Nullable Void> mono2) {
      return mono1.thenEmpty(mono2);
    }

    @AfterTemplate
    Mono<S> after(Mono<T> mono1, Mono<S> mono2) {
      return mono1.then(mono2);
    }
  }

  /** Avoid vacuous invocations of {@link Flux#ignoreElements()}. */
  static final class FluxThenMono<T, S> {
    @BeforeTemplate
    Mono<S> before(Flux<T> flux, Mono<S> mono) {
      return flux.ignoreElements().then(mono);
    }

    @BeforeTemplate
    Mono<@Nullable Void> before2(Flux<T> flux, Mono<@Nullable Void> mono) {
      return flux.thenEmpty(mono);
    }

    @AfterTemplate
    Mono<S> after(Flux<T> flux, Mono<S> mono) {
      return flux.then(mono);
    }
  }

  /** Prefer {@link Mono#singleOptional()} over more contrived alternatives. */
  // XXX: Consider creating a plugin that flags/discourages `Mono<Optional<T>>` method return
  // types, just as we discourage nullable `Boolean`s and `Optional`s.
  // XXX: The `mono.transform(Mono::singleOptional)` replacement is a special case of a more general
  // rule. Consider introducing an Error Prone check for this.
  static final class MonoSingleOptional<T> {
    @BeforeTemplate
    Mono<Optional<T>> before(Mono<T> mono) {
      return Refaster.anyOf(
          mono.flux().collect(toOptional()),
          mono.map(Optional::of).defaultIfEmpty(Optional.empty()),
          mono.transform(Mono::singleOptional));
    }

    @AfterTemplate
    @UseImportPolicy(STATIC_IMPORT_ALWAYS)
    Mono<Optional<T>> after(Mono<T> mono) {
      return mono.singleOptional();
    }
  }

  /** Prefer {@link Mono#cast(Class)} over {@link Mono#map(Function)} with a cast. */
  static final class MonoCast<T, S> {
    @BeforeTemplate
    Mono<S> before(Mono<T> mono) {
      return mono.map(Refaster.<S>clazz()::cast);
    }

    @AfterTemplate
    Mono<S> after(Mono<T> mono) {
      return mono.cast(Refaster.<S>clazz());
    }
  }

  /** Prefer {@link Flux#cast(Class)} over {@link Flux#map(Function)} with a cast. */
  static final class FluxCast<T, S> {
    @BeforeTemplate
    Flux<S> before(Flux<T> flux) {
      return flux.map(Refaster.<S>clazz()::cast);
    }

    @AfterTemplate
    Flux<S> after(Flux<T> flux) {
      return flux.cast(Refaster.<S>clazz());
    }
  }

  /** Prefer {@link Mono#ofType(Class)} over more contrived alternatives. */
  static final class MonoOfType<T, S> {
    @BeforeTemplate
    Mono<S> before(Mono<T> mono, Class<S> clazz) {
      return mono.filter(clazz::isInstance).cast(clazz);
    }

    @AfterTemplate
    Mono<S> after(Mono<T> mono, Class<S> clazz) {
      return mono.ofType(clazz);
    }
  }

  /** Prefer {@link Flux#ofType(Class)} over more contrived alternatives. */
  static final class FluxOfType<T, S> {
    @BeforeTemplate
    Flux<S> before(Flux<T> flux, Class<S> clazz) {
      return flux.filter(clazz::isInstance).cast(clazz);
    }

    @AfterTemplate
    Flux<S> after(Flux<T> flux, Class<S> clazz) {
      return flux.ofType(clazz);
    }
  }

  /** Prefer {@link Mono#flatMap(Function)} over more contrived alternatives. */
  static final class MonoFlatMap<S, T, P extends Mono<? extends T>> {
    @BeforeTemplate
    @SuppressWarnings("NestedPublishers")
    Mono<T> before(
        Mono<S> mono,
        Function<? super S, ? extends P> function,
        @Matches(IsIdentityOperation.class)
            Function<? super P, ? extends Mono<? extends T>> identityOperation) {
      return mono.map(function).flatMap(identityOperation);
    }

    @AfterTemplate
    Mono<T> after(Mono<S> mono, Function<? super S, ? extends P> function) {
      return mono.flatMap(function);
    }
  }

  /** Prefer {@link Mono#flatMapMany(Function)} over more contrived alternatives. */
  static final class MonoFlatMapMany<S, T, P extends Publisher<? extends T>> {
    @BeforeTemplate
    @SuppressWarnings("NestedPublishers")
    Flux<T> before(
        Mono<S> mono,
        Function<? super S, P> function,
        @Matches(IsIdentityOperation.class)
            Function<? super P, ? extends Publisher<? extends T>> identityOperation,
        int prefetch,
        boolean delayUntilEnd,
        int maxConcurrency) {
      return Refaster.anyOf(
          mono.map(function).flatMapMany(identityOperation),
          mono.flux().concatMap(function),
          mono.flux().concatMap(function, prefetch),
          mono.flux().concatMapDelayError(function),
          mono.flux().concatMapDelayError(function, prefetch),
          mono.flux().concatMapDelayError(function, delayUntilEnd, prefetch),
          mono.flux().flatMap(function, maxConcurrency),
          mono.flux().flatMap(function, maxConcurrency, prefetch),
          mono.flux().flatMapDelayError(function, maxConcurrency, prefetch),
          mono.flux().flatMapSequential(function, maxConcurrency),
          mono.flux().flatMapSequential(function, maxConcurrency, prefetch),
          mono.flux().flatMapSequentialDelayError(function, maxConcurrency, prefetch));
    }

    @BeforeTemplate
    Flux<T> before(Mono<S> mono, Function<? super S, Publisher<? extends T>> function) {
      return mono.flux().switchMap(function);
    }

    @AfterTemplate
    Flux<T> after(Mono<S> mono, Function<? super S, ? extends P> function) {
      return mono.flatMapMany(function);
    }
  }

  /**
   * Prefer {@link Flux#concatMapIterable(Function)} over alternatives that require an additional
   * subscription.
   */
  static final class ConcatMapIterableIdentity<T> {
    @BeforeTemplate
    Flux<T> before(Flux<? extends Iterable<T>> flux) {
      return Refaster.anyOf(
          flux.concatMap(list -> Flux.fromIterable(list)), flux.concatMap(Flux::fromIterable));
    }

    @AfterTemplate
    @UseImportPolicy(STATIC_IMPORT_ALWAYS)
    Flux<T> after(Flux<? extends Iterable<T>> flux) {
      return flux.concatMapIterable(identity());
    }
  }

  /**
   * Prefer {@link Flux#concatMapIterable(Function, int)} over alternatives that require an
   * additional subscription.
   */
  static final class ConcatMapIterableIdentityWithPrefetch<T> {
    @BeforeTemplate
    Flux<T> before(Flux<? extends Iterable<T>> flux, int prefetch) {
      return Refaster.anyOf(
          flux.concatMap(list -> Flux.fromIterable(list), prefetch),
          flux.concatMap(Flux::fromIterable, prefetch));
    }

    @AfterTemplate
    @UseImportPolicy(STATIC_IMPORT_ALWAYS)
    Flux<T> after(Flux<? extends Iterable<T>> flux, int prefetch) {
      return flux.concatMapIterable(identity(), prefetch);
    }
  }

  /**
   * Prefer {@link Flux#count()} followed by a conversion from {@code long} to {@code int} over
   * collecting into a list and counting its elements.
   */
  static final class FluxCountMapMathToIntExact<T> {
    @BeforeTemplate
    Mono<Integer> before(Flux<T> flux) {
      return Refaster.anyOf(
          flux.collect(toImmutableList())
              .map(
                  Refaster.anyOf(
                      Collection::size,
                      List::size,
                      ImmutableCollection::size,
                      ImmutableList::size)),
          flux.collect(toCollection(ArrayList::new))
              .map(Refaster.anyOf(Collection::size, List::size)));
    }

    @AfterTemplate
    Mono<Integer> after(Flux<T> flux) {
      return flux.count().map(Math::toIntExact);
    }
  }

  /**
   * Prefer {@link Mono#doOnError(Class, Consumer)} over {@link Mono#doOnError(Predicate, Consumer)}
   * where possible.
   */
  static final class MonoDoOnError<T> {
    @BeforeTemplate
    Mono<T> before(
        Mono<T> mono, Class<? extends Throwable> clazz, Consumer<? super Throwable> onError) {
      return mono.doOnError(clazz::isInstance, onError);
    }

    @AfterTemplate
    Mono<T> after(
        Mono<T> mono, Class<? extends Throwable> clazz, Consumer<? super Throwable> onError) {
      return mono.doOnError(clazz, onError);
    }
  }

  /**
   * Prefer {@link Flux#doOnError(Class, Consumer)} over {@link Flux#doOnError(Predicate, Consumer)}
   * where possible.
   */
  static final class FluxDoOnError<T> {
    @BeforeTemplate
    Flux<T> before(
        Flux<T> flux, Class<? extends Throwable> clazz, Consumer<? super Throwable> onError) {
      return flux.doOnError(clazz::isInstance, onError);
    }

    @AfterTemplate
    Flux<T> after(
        Flux<T> flux, Class<? extends Throwable> clazz, Consumer<? super Throwable> onError) {
      return flux.doOnError(clazz, onError);
    }
  }

  /** Prefer {@link Mono#onErrorComplete()} over more contrived alternatives. */
  static final class MonoOnErrorComplete<T> {
    @BeforeTemplate
    Mono<T> before(Mono<T> mono) {
      return mono.onErrorResume(e -> Mono.empty());
    }

    @AfterTemplate
    Mono<T> after(Mono<T> mono) {
      return mono.onErrorComplete();
    }
  }

  /** Prefer {@link Flux#onErrorComplete()} over more contrived alternatives. */
  static final class FluxOnErrorComplete<T> {
    @BeforeTemplate
    Flux<T> before(Flux<T> flux) {
      return flux.onErrorResume(e -> Refaster.anyOf(Mono.empty(), Flux.empty()));
    }

    @AfterTemplate
    Flux<T> after(Flux<T> flux) {
      return flux.onErrorComplete();
    }
  }

  /** Prefer {@link Mono#onErrorComplete(Class)}} over more contrived alternatives. */
  static final class MonoOnErrorCompleteClass<T> {
    @BeforeTemplate
    Mono<T> before(Mono<T> mono, Class<? extends Throwable> clazz) {
      return Refaster.anyOf(
          mono.onErrorComplete(clazz::isInstance), mono.onErrorResume(clazz, e -> Mono.empty()));
    }

    @AfterTemplate
    Mono<T> after(Mono<T> mono, Class<? extends Throwable> clazz) {
      return mono.onErrorComplete(clazz);
    }
  }

  /** Prefer {@link Flux#onErrorComplete(Class)}} over more contrived alternatives. */
  static final class FluxOnErrorCompleteClass<T> {
    @BeforeTemplate
    Flux<T> before(Flux<T> flux, Class<? extends Throwable> clazz) {
      return Refaster.anyOf(
          flux.onErrorComplete(clazz::isInstance),
          flux.onErrorResume(clazz, e -> Refaster.anyOf(Mono.empty(), Flux.empty())));
    }

    @AfterTemplate
    Flux<T> after(Flux<T> flux, Class<? extends Throwable> clazz) {
      return flux.onErrorComplete(clazz);
    }
  }

  /** Prefer {@link Mono#onErrorComplete(Predicate)}} over more contrived alternatives. */
  static final class MonoOnErrorCompletePredicate<T> {
    @BeforeTemplate
    Mono<T> before(Mono<T> mono, Predicate<? super Throwable> predicate) {
      return mono.onErrorResume(predicate, e -> Mono.empty());
    }

    @AfterTemplate
    Mono<T> after(Mono<T> mono, Predicate<? super Throwable> predicate) {
      return mono.onErrorComplete(predicate);
    }
  }

  /** Prefer {@link Flux#onErrorComplete(Predicate)}} over more contrived alternatives. */
  static final class FluxOnErrorCompletePredicate<T> {
    @BeforeTemplate
    Flux<T> before(Flux<T> flux, Predicate<? super Throwable> predicate) {
      return flux.onErrorResume(predicate, e -> Refaster.anyOf(Mono.empty(), Flux.empty()));
    }

    @AfterTemplate
    Flux<T> after(Flux<T> flux, Predicate<? super Throwable> predicate) {
      return flux.onErrorComplete(predicate);
    }
  }

  /**
   * Prefer {@link Mono#onErrorContinue(Class, BiConsumer)} over {@link
   * Mono#onErrorContinue(Predicate, BiConsumer)} where possible.
   */
  static final class MonoOnErrorContinue<T> {
    @BeforeTemplate
    Mono<T> before(
        Mono<T> mono,
        Class<? extends Throwable> clazz,
        BiConsumer<Throwable, Object> errorConsumer) {
      return mono.onErrorContinue(clazz::isInstance, errorConsumer);
    }

    @AfterTemplate
    Mono<T> after(
        Mono<T> mono,
        Class<? extends Throwable> clazz,
        BiConsumer<Throwable, Object> errorConsumer) {
      return mono.onErrorContinue(clazz, errorConsumer);
    }
  }

  /**
   * Prefer {@link Flux#onErrorContinue(Class, BiConsumer)} over {@link
   * Flux#onErrorContinue(Predicate, BiConsumer)} where possible.
   */
  static final class FluxOnErrorContinue<T> {
    @BeforeTemplate
    Flux<T> before(
        Flux<T> flux,
        Class<? extends Throwable> clazz,
        BiConsumer<Throwable, Object> errorConsumer) {
      return flux.onErrorContinue(clazz::isInstance, errorConsumer);
    }

    @AfterTemplate
    Flux<T> after(
        Flux<T> flux,
        Class<? extends Throwable> clazz,
        BiConsumer<Throwable, Object> errorConsumer) {
      return flux.onErrorContinue(clazz, errorConsumer);
    }
  }

  /**
   * Prefer {@link Mono#onErrorMap(Class, Function)} over {@link Mono#onErrorMap(Predicate,
   * Function)} where possible.
   */
  static final class MonoOnErrorMap<T> {
    @BeforeTemplate
    Mono<T> before(
        Mono<T> mono,
        Class<? extends Throwable> clazz,
        Function<? super Throwable, ? extends Throwable> mapper) {
      return mono.onErrorMap(clazz::isInstance, mapper);
    }

    @AfterTemplate
    Mono<T> after(
        Mono<T> mono,
        Class<? extends Throwable> clazz,
        Function<? super Throwable, ? extends Throwable> mapper) {
      return mono.onErrorMap(clazz, mapper);
    }
  }

  /**
   * Prefer {@link Flux#onErrorMap(Class, Function)} over {@link Flux#onErrorMap(Predicate,
   * Function)} where possible.
   */
  static final class FluxOnErrorMap<T> {
    @BeforeTemplate
    Flux<T> before(
        Flux<T> flux,
        Class<? extends Throwable> clazz,
        Function<? super Throwable, ? extends Throwable> mapper) {
      return flux.onErrorMap(clazz::isInstance, mapper);
    }

    @AfterTemplate
    Flux<T> after(
        Flux<T> flux,
        Class<? extends Throwable> clazz,
        Function<? super Throwable, ? extends Throwable> mapper) {
      return flux.onErrorMap(clazz, mapper);
    }
  }

  /**
   * Prefer {@link Mono#onErrorResume(Class, Function)} over {@link Mono#onErrorResume(Predicate,
   * Function)} where possible.
   */
  static final class MonoOnErrorResume<T> {
    @BeforeTemplate
    Mono<T> before(
        Mono<T> mono,
        Class<? extends Throwable> clazz,
        Function<? super Throwable, ? extends Mono<? extends T>> fallback) {
      return mono.onErrorResume(clazz::isInstance, fallback);
    }

    @AfterTemplate
    Mono<T> after(
        Mono<T> mono,
        Class<? extends Throwable> clazz,
        Function<? super Throwable, ? extends Mono<? extends T>> fallback) {
      return mono.onErrorResume(clazz, fallback);
    }
  }

  /**
   * Prefer {@link Flux#onErrorResume(Class, Function)} over {@link Flux#onErrorResume(Predicate,
   * Function)} where possible.
   */
  static final class FluxOnErrorResume<T> {
    @BeforeTemplate
    Flux<T> before(
        Flux<T> flux,
        Class<? extends Throwable> clazz,
        Function<? super Throwable, ? extends Publisher<? extends T>> fallback) {
      return flux.onErrorResume(clazz::isInstance, fallback);
    }

    @AfterTemplate
    Flux<T> after(
        Flux<T> flux,
        Class<? extends Throwable> clazz,
        Function<? super Throwable, ? extends Publisher<? extends T>> fallback) {
      return flux.onErrorResume(clazz, fallback);
    }
  }

  /**
   * Prefer {@link Mono#onErrorReturn(Class, Object)} over {@link Mono#onErrorReturn(Predicate,
   * Object)} where possible.
   */
  static final class MonoOnErrorReturn<T> {
    @BeforeTemplate
    Mono<T> before(Mono<T> mono, Class<? extends Throwable> clazz, T fallbackValue) {
      return mono.onErrorReturn(clazz::isInstance, fallbackValue);
    }

    @AfterTemplate
    Mono<T> after(Mono<T> mono, Class<? extends Throwable> clazz, T fallbackValue) {
      return mono.onErrorReturn(clazz, fallbackValue);
    }
  }

  /**
   * Prefer {@link Flux#onErrorReturn(Class, Object)} over {@link Flux#onErrorReturn(Predicate,
   * Object)} where possible.
   */
  static final class FluxOnErrorReturn<T> {
    @BeforeTemplate
    Flux<T> before(Flux<T> flux, Class<? extends Throwable> clazz, T fallbackValue) {
      return flux.onErrorReturn(clazz::isInstance, fallbackValue);
    }

    @AfterTemplate
    Flux<T> after(Flux<T> flux, Class<? extends Throwable> clazz, T fallbackValue) {
      return flux.onErrorReturn(clazz, fallbackValue);
    }
  }

  /**
   * Apply {@link Flux#filter(Predicate)} before {@link Flux#sort()} to reduce the number of
   * elements to sort.
   */
  static final class FluxFilterSort<T> {
    @BeforeTemplate
    Flux<T> before(Flux<T> flux, Predicate<? super T> predicate) {
      return flux.sort().filter(predicate);
    }

    @AfterTemplate
    Flux<T> after(Flux<T> flux, Predicate<? super T> predicate) {
      return flux.filter(predicate).sort();
    }
  }

  /**
   * Apply {@link Flux#filter(Predicate)} before {@link Flux#sort(Comparator)} to reduce the number
   * of elements to sort.
   */
  static final class FluxFilterSortWithComparator<T> {
    @BeforeTemplate
    Flux<T> before(Flux<T> flux, Predicate<? super T> predicate, Comparator<? super T> comparator) {
      return flux.sort(comparator).filter(predicate);
    }

    @AfterTemplate
    Flux<T> after(Flux<T> flux, Predicate<? super T> predicate, Comparator<? super T> comparator) {
      return flux.filter(predicate).sort(comparator);
    }
  }

  /**
   * Do not unnecessarily {@link Flux#filter(Predicate) filter} the result of {@link
   * Flux#takeWhile(Predicate)} using the same {@link Predicate}.
   */
  static final class FluxTakeWhile<T> {
    @BeforeTemplate
    Flux<T> before(Flux<T> flux, Predicate<? super T> predicate) {
      return flux.takeWhile(predicate).filter(predicate);
    }

    @AfterTemplate
    Flux<T> after(Flux<T> flux, Predicate<? super T> predicate) {
      return flux.takeWhile(predicate);
    }
  }

  /**
   * Prefer {@link Flux#collect(Collector)} with {@link ImmutableList#toImmutableList()} over
   * alternatives that do not explicitly return an immutable collection.
   */
  static final class FluxCollectToImmutableList<T> {
    @BeforeTemplate
    Mono<List<T>> before(Flux<T> flux) {
      return flux.collectList();
    }

    @AfterTemplate
    @UseImportPolicy(STATIC_IMPORT_ALWAYS)
    Mono<ImmutableList<T>> after(Flux<T> flux) {
      return flux.collect(toImmutableList());
    }
  }

  /**
   * Prefer {@link Flux#collect(Collector)} with {@link ImmutableSet#toImmutableSet()} over more
   * contrived alternatives.
   */
  static final class FluxCollectToImmutableSet<T> {
    @BeforeTemplate
    Mono<ImmutableSet<T>> before(Flux<T> flux) {
      return flux.collect(toImmutableList()).map(ImmutableSet::copyOf);
    }

    @AfterTemplate
    @UseImportPolicy(STATIC_IMPORT_ALWAYS)
    Mono<ImmutableSet<T>> after(Flux<T> flux) {
      return flux.collect(toImmutableSet());
    }
  }

  /** Prefer {@link Flux#sort()} over more verbose alternatives. */
  static final class FluxSort<T extends Comparable<? super T>> {
    @BeforeTemplate
    Flux<T> before(Flux<T> flux) {
      return flux.sort(naturalOrder());
    }

    @AfterTemplate
    Flux<T> after(Flux<T> flux) {
      return flux.sort();
    }
  }

  /** Prefer {@link MathFlux#min(Publisher)} over less efficient alternatives. */
  static final class FluxTransformMin<T extends Comparable<? super T>> {
    @BeforeTemplate
    Mono<T> before(Flux<T> flux) {
      return flux.sort().next();
    }

    @AfterTemplate
    Mono<T> after(Flux<T> flux) {
      return flux.transform(MathFlux::min).singleOrEmpty();
    }
  }

  /**
   * Prefer {@link MathFlux#min(Publisher, Comparator)} over less efficient or more verbose
   * alternatives.
   */
  static final class FluxTransformMinWithComparator<T extends Comparable<? super T>> {
    @BeforeTemplate
    Mono<T> before(Flux<T> flux, Comparator<? super T> cmp) {
      return Refaster.anyOf(
          flux.sort(cmp).next(), flux.collect(minBy(cmp)).flatMap(Mono::justOrEmpty));
    }

    @AfterTemplate
    Mono<T> after(Flux<T> flux, Comparator<? super T> cmp) {
      return flux.transform(f -> MathFlux.min(f, cmp)).singleOrEmpty();
    }
  }

  /** Prefer {@link MathFlux#max(Publisher)} over less efficient alternatives. */
  static final class FluxTransformMax<T extends Comparable<? super T>> {
    @BeforeTemplate
    Mono<T> before(Flux<T> flux) {
      return flux.sort().last();
    }

    @AfterTemplate
    Mono<T> after(Flux<T> flux) {
      return flux.transform(MathFlux::max).singleOrEmpty();
    }
  }

  /**
   * Prefer {@link MathFlux#max(Publisher, Comparator)} over less efficient or more verbose
   * alternatives.
   */
  static final class FluxTransformMaxWithComparator<T extends Comparable<? super T>> {
    @BeforeTemplate
    Mono<T> before(Flux<T> flux, Comparator<? super T> cmp) {
      return Refaster.anyOf(
          flux.sort(cmp).last(), flux.collect(maxBy(cmp)).flatMap(Mono::justOrEmpty));
    }

    @AfterTemplate
    Mono<T> after(Flux<T> flux, Comparator<? super T> cmp) {
      return flux.transform(f -> MathFlux.max(f, cmp)).singleOrEmpty();
    }
  }

  /** Prefer {@link MathFlux#min(Publisher)} over more contrived alternatives. */
  static final class MathFluxMin<T extends Comparable<? super T>> {
    @BeforeTemplate
    Mono<T> before(Publisher<T> publisher) {
      return Refaster.anyOf(
          MathFlux.min(publisher, naturalOrder()), MathFlux.max(publisher, reverseOrder()));
    }

    @AfterTemplate
    Mono<T> after(Publisher<T> publisher) {
      return MathFlux.min(publisher);
    }
  }

  /** Prefer {@link MathFlux#max(Publisher)} over more contrived alternatives. */
  static final class MathFluxMax<T extends Comparable<? super T>> {
    @BeforeTemplate
    Mono<T> before(Publisher<T> publisher) {
      return Refaster.anyOf(
          MathFlux.min(publisher, reverseOrder()), MathFlux.max(publisher, naturalOrder()));
    }

    @AfterTemplate
    Mono<T> after(Publisher<T> publisher) {
      return MathFlux.max(publisher);
    }
  }

  /** Prefer {@link reactor.util.context.Context#empty()}} over more verbose alternatives. */
  // XXX: Introduce Refaster rules or a `BugChecker` that maps `(Immutable)Map.of(k, v)` to
  // `Context.of(k, v)` and likewise for multi-pair overloads.
  static final class ContextEmpty {
    @BeforeTemplate
    Context before(@Matches(IsEmpty.class) Map<?, ?> map) {
      return Context.of(map);
    }

    @AfterTemplate
    Context after() {
      return Context.empty();
    }
  }

  /** Prefer {@link PublisherProbe#empty()}} over more verbose alternatives. */
  static final class PublisherProbeEmpty<T> {
    @BeforeTemplate
    PublisherProbe<T> before() {
      return PublisherProbe.of(Refaster.anyOf(Mono.empty(), Flux.empty()));
    }

    @AfterTemplate
    PublisherProbe<T> after() {
      return PublisherProbe.empty();
    }
  }

  /** Prefer {@link Mono#as(Function)} when creating a {@link StepVerifier}. */
  static final class StepVerifierFromMono<T> {
    @BeforeTemplate
    StepVerifier.FirstStep<? extends T> before(Mono<T> mono) {
      return Refaster.anyOf(StepVerifier.create(mono), mono.flux().as(StepVerifier::create));
    }

    @AfterTemplate
    StepVerifier.FirstStep<? extends T> after(Mono<T> mono) {
      return mono.as(StepVerifier::create);
    }
  }

  /** Prefer {@link Flux#as(Function)} when creating a {@link StepVerifier}. */
  static final class StepVerifierFromFlux<T> {
    @BeforeTemplate
    StepVerifier.FirstStep<? extends T> before(Flux<T> flux) {
      return StepVerifier.create(flux);
    }

    @AfterTemplate
    StepVerifier.FirstStep<? extends T> after(Flux<T> flux) {
      return flux.as(StepVerifier::create);
    }
  }

  /** Don't unnecessarily have {@link StepVerifier.Step} expect no elements. */
  static final class StepVerifierStepIdentity<T> {
    @BeforeTemplate
    @SuppressWarnings("unchecked")
    StepVerifier.Step<T> before(
        StepVerifier.Step<T> step, @Matches(IsEmpty.class) Iterable<? extends T> iterable) {
      return Refaster.anyOf(
          step.expectNext(), step.expectNextCount(0), step.expectNextSequence(iterable));
    }

    @AfterTemplate
    @CanIgnoreReturnValue
    StepVerifier.Step<T> after(StepVerifier.Step<T> step) {
      return step;
    }
  }

  /** Prefer {@link StepVerifier.Step#expectNext(Object)} over more verbose alternatives. */
  static final class StepVerifierStepExpectNext<T> {
    @BeforeTemplate
    StepVerifier.Step<T> before(StepVerifier.Step<T> step, T object) {
      return Refaster.anyOf(
          step.expectNextMatches(e -> e.equals(object)), step.expectNextMatches(object::equals));
    }

    @AfterTemplate
    StepVerifier.Step<T> after(StepVerifier.Step<T> step, T object) {
      return step.expectNext(object);
    }
  }

  /** Avoid list collection when verifying that a {@link Flux} emits exactly one value. */
  // XXX: This rule assumes that the matched collector does not drop elements. Consider introducing
  // a `@Matches(DoesNotDropElements.class)` or `@NotMatches(MayDropElements.class)` guard.
  static final class FluxAsStepVerifierExpectNext<T, L extends List<T>> {
    @BeforeTemplate
    StepVerifier.Step<L> before(Flux<T> flux, T object, Collector<? super T, ?, L> listCollector) {
      return flux.collect(listCollector)
          .as(StepVerifier::create)
          .assertNext(list -> assertThat(list).containsExactly(object));
    }

    @AfterTemplate
    StepVerifier.Step<T> after(Flux<T> flux, T object) {
      return flux.as(StepVerifier::create).expectNext(object);
    }
  }

  /** Prefer {@link StepVerifier.LastStep#verifyComplete()} over more verbose alternatives. */
  static final class StepVerifierLastStepVerifyComplete {
    @BeforeTemplate
    Duration before(StepVerifier.LastStep step) {
      return step.expectComplete().verify();
    }

    @AfterTemplate
    Duration after(StepVerifier.LastStep step) {
      return step.verifyComplete();
    }
  }

  /** Prefer {@link StepVerifier.LastStep#verifyError()} over more verbose alternatives. */
  static final class StepVerifierLastStepVerifyError {
    @BeforeTemplate
    Duration before(StepVerifier.LastStep step) {
      return step.expectError().verify();
    }

    @AfterTemplate
    Duration after(StepVerifier.LastStep step) {
      return step.verifyError();
    }
  }

  /** Prefer {@link StepVerifier.LastStep#verifyError(Class)} over more verbose alternatives. */
  static final class StepVerifierLastStepVerifyErrorClass<T extends Throwable> {
    @BeforeTemplate
    Duration before(StepVerifier.LastStep step, Class<T> clazz) {
      return Refaster.anyOf(
          step.expectError(clazz).verify(),
          step.verifyErrorMatches(clazz::isInstance),
          step.verifyErrorSatisfies(t -> assertThat(t).isInstanceOf(clazz)));
    }

    @AfterTemplate
    Duration after(StepVerifier.LastStep step, Class<T> clazz) {
      return step.verifyError(clazz);
    }
  }

  /**
   * Prefer {@link StepVerifier.LastStep#verifyErrorMatches(Predicate)} over more verbose
   * alternatives.
   */
  static final class StepVerifierLastStepVerifyErrorMatches {
    @BeforeTemplate
    Duration before(StepVerifier.LastStep step, Predicate<Throwable> predicate) {
      return step.expectErrorMatches(predicate).verify();
    }

    @AfterTemplate
    Duration after(StepVerifier.LastStep step, Predicate<Throwable> predicate) {
      return step.verifyErrorMatches(predicate);
    }
  }

  /**
   * Prefer {@link StepVerifier.LastStep#verifyErrorSatisfies(Consumer)} over more verbose
   * alternatives.
   */
  static final class StepVerifierLastStepVerifyErrorSatisfies {
    @BeforeTemplate
    Duration before(StepVerifier.LastStep step, Consumer<Throwable> consumer) {
      return step.expectErrorSatisfies(consumer).verify();
    }

    @AfterTemplate
    Duration after(StepVerifier.LastStep step, Consumer<Throwable> consumer) {
      return step.verifyErrorSatisfies(consumer);
    }
  }

  /**
   * Prefer {@link StepVerifier.LastStep#verifyErrorMessage(String)} over more verbose alternatives.
   */
  static final class StepVerifierLastStepVerifyErrorMessage {
    @BeforeTemplate
    Duration before(StepVerifier.LastStep step, String message) {
      return step.expectErrorMessage(message).verify();
    }

    @AfterTemplate
    Duration after(StepVerifier.LastStep step, String message) {
      return step.verifyErrorMessage(message);
    }
  }

  /**
   * Prefer {@link StepVerifier.LastStep#verifyTimeout(Duration)} over more verbose alternatives.
   */
  static final class StepVerifierLastStepVerifyTimeout {
    @BeforeTemplate
    Duration before(StepVerifier.LastStep step, Duration duration) {
      return step.expectTimeout(duration).verify();
    }

    @AfterTemplate
    Duration after(StepVerifier.LastStep step, Duration duration) {
      return step.verifyTimeout(duration);
    }
  }
}

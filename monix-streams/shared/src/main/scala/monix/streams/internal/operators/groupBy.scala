/*
 * Copyright (c) 2014-2016 by its authors. Some rights reserved.
 * See the project homepage at: https://monix.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package monix.streams.internal.operators

import monix.execution.Cancelable
import monix.base.atomic.padded.Atomic
import monix.streams.{OverflowStrategy, Observer, Observable, Ack}
import monix.streams.Ack.{Continue, Cancel}
import monix.streams.internal._
import monix.streams.exceptions.CompositeException
import monix.streams.observers.BufferedSubscriber
import monix.streams.observables.GroupedObservable
import scala.annotation.tailrec
import scala.concurrent.Future
import scala.util.control.NonFatal

private[monix] object groupBy {
  /** Implementation for [[Observable.groupBy]] */
  def apply[T,K](source: Observable[T], os: OverflowStrategy.Synchronous, keyFn: T => K): Observable[GroupedObservable[K,T]] = {
    Observable.unsafeCreate { subscriber =>
      import subscriber.{scheduler => s}

      source.unsafeSubscribeFn(new Observer[T] { self =>
        private[this] var isDone = false
        private[this] val downstream = BufferedSubscriber(subscriber, os)
        private[this] val cacheRef = Atomic(Map.empty[K, Observer[T]])

        @tailrec
        private[this] def recycleKey(key: K): Unit = {
          val current = cacheRef.get
          if (!cacheRef.compareAndSet(current, current - key))
            recycleKey(key)
        }

        @tailrec
        def onNext(elem: T): Future[Ack] = {
          if (isDone) Cancel else {
            val cache = cacheRef.get
            var streamError = true

            val result = try {
              val key = keyFn(elem)
              streamError = false

              if (cache.contains(key)) {
                cache(key).onNext(elem)
                  // if downstream cancels, we retry
                  .onCancelStreamOnNext(self, elem)
              }
              else {
                val onCancel = Cancelable(recycleKey(key))
                val (observer, observable) =
                  GroupedObservable.broadcast[K,T](key, onCancel)

                if (cacheRef.compareAndSet(cache, cache.updated(key, observer)))
                  downstream.onNext(observable).fastFlatMap {
                    case Continue =>
                      // pushing the first element
                      observer.onNext(elem).mapToContinue

                    case Cancel =>
                      val errors = completeAll()
                      if (errors.nonEmpty)
                        self.onError(CompositeException(errors))
                      Cancel
                  }
                else
                  null // this will trigger a tailrec retry
              }
            }
            catch {
              case NonFatal(ex) =>
                if (!streamError) Future.failed(ex) else {
                  self.onError(ex)
                  Cancel
                }
            }

            if (result == null)
              onNext(elem)
            else
              result
          }
        }

        private[this] def completeAll(): Seq[Throwable] = {
          val cache = cacheRef.get

          if (!cacheRef.compareAndSet(cache, Map.empty))
            completeAll()
          else
            cache.values.foldLeft(Vector.empty[Throwable]) { (acc, o) =>
              try {
                o.onComplete()
                acc
              }
              catch {
                case NonFatal(ex) =>
                  acc :+ ex
              }
            }
        }

        def onError(ex: Throwable): Unit = {
          if (!isDone) {
            isDone = true
            val errors = completeAll()
            if (errors.nonEmpty)
              downstream.onError(CompositeException(ex +: errors))
            else
              downstream.onError(ex)
          }
        }

        def onComplete(): Unit = {
          if (!isDone) {
            isDone = true
            val errors = completeAll()
            if (errors.nonEmpty)
              downstream.onError(CompositeException(errors))
            else
              downstream.onComplete()
          }
        }
      })
    }
  }
}

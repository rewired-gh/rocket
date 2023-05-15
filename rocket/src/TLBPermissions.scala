// See LICENSE.SiFive for license details.

package org.chipsalliance.rocket

import chisel3._
import chisel3.util._

import org.chipsalliance.rocket.util._

case class TLBPermissions(
  homogeneous: Bool, // if false, the below are undefined
  r: Bool, // readable
  w: Bool, // writeable
  x: Bool, // executable
  c: Bool, // cacheable
  a: Bool, // arithmetic ops
  l: Bool) // logical ops

object TLBPageLookup
{
  private case class TLBFixedPermissions(
    e: Boolean, // get-/put-effects
    r: Boolean, // readable
    w: Boolean, // writeable
    x: Boolean, // executable
    c: Boolean, // cacheable
    a: Boolean, // arithmetic ops
    l: Boolean) { // logical ops
    val useful = r || w || x || c || a || l
  }

  private def groupRegions(memParameters: Seq[MemoryParameters]): Map[TLBFixedPermissions, Seq[AddressSet]] = { // TODO: Decoupled from Tilelink
    val permissions = memParameters.map { p =>
      (p.address, TLBFixedPermissions(
        e = p.hasPutEffects   || p.hasGetEffects,
        r = p.supportsGet     || p.supportsAcquireB, // if cached, never uses Get
        w = p.supportsPutFull || p.supportsAcquireT, // if cached, never uses Put
        x = p.executable,
        c = p.supportsAcquireB,
        a = p.supportsArithmetic,
        l = p.supportsLogical))
    }

    permissions
      .filter(_._2.useful) // get rid of no-permission devices
      .groupBy(_._2) // group by permission type
      .mapValues(seq =>
        AddressSet.unify(seq.flatMap(_._1))) // coalesce same-permission regions
      .toMap
  }

  // TODO
  // Unmapped memory is considered to be inhomogeneous
  def apply(memParameters: Seq[MemoryParameters], xLen: Int, cacheBlockBytes: Int, pageSize: BigInt): UInt => TLBPermissions = {
    require (isPow2(xLen) && xLen >= 8)
    require (isPow2(cacheBlockBytes) && cacheBlockBytes >= xLen/8)
    require (isPow2(pageSize) && pageSize >= cacheBlockBytes)

    val xferSizes = TransferSizes(cacheBlockBytes, cacheBlockBytes)
    val allSizes = TransferSizes(1, cacheBlockBytes)
    val amoSizes = TransferSizes(4, xLen/8)

    val permissions = memParameters.foreach { p =>
      require (!p.supportsGet        || p.supportsGet       .contains(allSizes),  s"Memory region '${p.name}' at ${p.address} only supports ${p.supportsGet} Get, but must support ${allSizes}")
      require (!p.supportsPutFull    || p.supportsPutFull   .contains(allSizes),  s"Memory region '${p.name}' at ${p.address} only supports ${p.supportsPutFull} PutFull, but must support ${allSizes}")
      require (!p.supportsPutPartial || p.supportsPutPartial.contains(allSizes),  s"Memory region '${p.name}' at ${p.address} only supports ${p.supportsPutPartial} PutPartial, but must support ${allSizes}")
      require (!p.supportsAcquireB   || p.supportsAcquireB  .contains(xferSizes), s"Memory region '${p.name}' at ${p.address} only supports ${p.supportsAcquireB} AcquireB, but must support ${xferSizes}")
      require (!p.supportsAcquireT   || p.supportsAcquireT  .contains(xferSizes), s"Memory region '${p.name}' at ${p.address} only supports ${p.supportsAcquireT} AcquireT, but must support ${xferSizes}")
      require (!p.supportsLogical    || p.supportsLogical   .contains(amoSizes),  s"Memory region '${p.name}' at ${p.address} only supports ${p.supportsLogical} Logical, but must support ${amoSizes}")
      require (!p.supportsArithmetic || p.supportsArithmetic.contains(amoSizes),  s"Memory region '${p.name}' at ${p.address} only supports ${p.supportsArithmetic} Arithmetic, but must support ${amoSizes}")
      require (!(p.supportsAcquireB && p.supportsPutFull && !p.supportsAcquireT), s"Memory region '${p.name}' supports AcquireB (cached read) and PutFull (un-cached write) but not AcquireT (cached write)")
    }

    val grouped = groupRegions(memParameters)
      .mapValues(_.filter(_.alignment >= pageSize)) // discard any region that's not big enough

    def lowCostProperty(prop: TLBFixedPermissions => Boolean): UInt => Bool = {
      val (yesm, nom) = grouped.partition { case (k, eq) => prop(k) }
      val (yes, no) = (yesm.values.flatten.toList, nom.values.flatten.toList)
      // Find the minimal bits needed to distinguish between yes and no
      val decisionMask = AddressDecoder(Seq(yes, no))
      def simplify(x: Seq[AddressSet]) = AddressSet.unify(x.map(_.widen(~decisionMask)).distinct)
      val (yesf, nof) = (simplify(yes), simplify(no))
      if (yesf.size < no.size) {
        (x: UInt) => yesf.map(_.contains(x)).foldLeft(false.B)(_ || _)
      } else {
        (x: UInt) => !nof.map(_.contains(x)).foldLeft(false.B)(_ || _)
      }
    }

    // Derive simplified property circuits (don't care when !homo)
    val rfn = lowCostProperty(_.r)
    val wfn = lowCostProperty(_.w)
    val xfn = lowCostProperty(_.x)
    val cfn = lowCostProperty(_.c)
    val afn = lowCostProperty(_.a)
    val lfn = lowCostProperty(_.l)

    val homo = AddressSet.unify(grouped.values.flatten.toList)
    (x: UInt) => TLBPermissions(
      homogeneous = homo.map(_.contains(x)).foldLeft(false.B)(_ || _),
      r = rfn(x),
      w = wfn(x),
      x = xfn(x),
      c = cfn(x),
      a = afn(x),
      l = lfn(x))
  }

  // Are all pageSize intervals of mapped regions homogeneous?
  def homogeneous(memParameters: Seq[MemoryParameters], pageSize: BigInt): Boolean = {
    groupRegions(memParameters).values.forall(_.forall(_.alignment >= pageSize))
  }
}

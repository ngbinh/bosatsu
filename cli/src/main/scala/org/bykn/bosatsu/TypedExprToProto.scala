package org.bykn.bosatsu

import _root_.bosatsu.{TypedAst => proto}
import cats.{Foldable, Monad, MonadError}
import cats.data.StateT
import cats.effect.IO
import java.nio.file.Path
import java.io.{FileInputStream, FileOutputStream, BufferedInputStream, BufferedOutputStream}
import org.bykn.bosatsu.rankn.{Type, DefinedType}
import scala.util.{Failure, Success, Try}

import cats.implicits._

/**
 * convert TypedExpr to and from Protobuf representation
 */
object ProtoConverter {
  case class SerState(
    strings: Map[String, Int],
    stringVec: Vector[String],
    types: Map[Type, Int],
    typeVec: Vector[proto.Type]) {

    def stringId(s: String): Either[(SerState, Int), Int] =
      strings.get(s) match {
        case Some(id) => Right(id)
        case None =>
          val id = stringVec.size
          val nextSS = copy(
            strings = strings.updated(s, id),
            stringVec = stringVec :+ s)
          Left((nextSS, id))
      }

    def typeId(t: Type, protoType: => proto.Type): Either[(SerState, Int), Int] =
      types.get(t) match {
        case Some(id) => Right(id)
        case None =>
          val id = typeVec.size
          val nextSS = copy(
            types = types.updated(t, id),
            typeVec = typeVec :+ protoType)
          Left((nextSS, id))
      }

    def getProtoType(t: Type): Option[(proto.Type, Int)] =
      types.get(t).map { idx => (typeVec(idx), idx) }

    def getString(idx: Int): Option[String] =
      if ((0 <= idx) && (idx < stringVec.size)) Some(stringVec(idx))
      else None

    def getType(idx: Int): Option[proto.Type] =
      if ((0 <= idx) && (idx < typeVec.size)) Some(typeVec(idx))
      else None
  }

  object SerState {
    val empty: SerState =
      SerState(Map.empty, Vector.empty, Map.empty, Vector.empty)

    def fromVectors(strings: Vector[String], types: Vector[proto.Type]): SerState =
      SerState(Map.empty, strings, Map.empty, types)
  }

  type Tab[A] = StateT[Try, SerState, A]

  private def tabFail[S, A](ex: Exception): Tab[A] =
    MonadError[Tab, Throwable].raiseError(ex)

  private def tabPure[S, A](a: A): Tab[A] =
    Monad[Tab].pure(a)

  private def lift[S, A](ta: Try[A]): Tab[A] =
    StateT.liftF(ta)

  private def get(fn: SerState => Either[(SerState, Int), Int]): Tab[Int] =
    StateT.get[Try, SerState]
      .flatMap { ss =>
        fn(ss) match {
           case Right(idx) => StateT.pure(idx + 1)
           case Left((ss, idx)) =>
             StateT.set[Try, SerState](ss).as(idx + 1)
         }
      }

  private def find[A](idx: Int, context: => String)(fn: (SerState, Int) => Option[A]): Tab[A] =
    StateT.get[Try, SerState]
      .flatMap { ss =>
        fn(ss, idx - 1) match {
          case Some(s) => tabPure(s)
          case None => tabFail(new Exception(s"invalid index: $idx in $context"))
        }
      }

  private def getId(s: String): Tab[Int] = get(_.stringId(s))

  private def lookup(idx: Int, context: => String): Tab[String] =
    find(idx, context)(_.getString(_))

  private def getTypeId(t: Type, pt: => proto.Type): Tab[(proto.Type, Int)] =
    for {
      idx <- get(_.typeId(t, pt))
      st <- StateT.get[Try, SerState]
    } yield (st.typeVec(idx - 1), idx)

  private def getProtoTypeTab(t: Type): Tab[Option[(proto.Type, Int)]] =
    StateT.get[Try, SerState]
      .map(_.getProtoType(t).map { case (t, idx) => (t, idx + 1) })

  private def lookupType(idx: Int, context: => String): Tab[proto.Type] =
    find(idx, context)(_.getType(_))

  def runTab[A](t: Tab[A]): Try[(SerState, A)] =
    t.run(SerState.empty)

  def runLookupTab[A](strings: Vector[String], types: Vector[proto.Type], t: Tab[A]): Try[A] =
    t.runA(SerState.fromVectors(strings, types))

  def typeConstFromProto(p: proto.TypeConst): Tab[Type.Const.Defined] = {
    val proto.TypeConst(packidx, tidx) = p
    lookup(packidx, s"package in: $p")
      .product(lookup(tidx, s"type in: $p"))
      .flatMap { case (pack, t) =>
        PackageName.parse(pack) match {
          case None =>
            tabFail(new Exception(s"invalid package name: $pack, in $p"))
          case Some(pack) =>
            lift(Try {
              val cons = Identifier.unsafeParse(Identifier.consParser, t)
              Type.Const.Defined(pack, TypeName(cons))
            })
        }
      }
  }

  def typeConstToProto(tc: Type.Const): Tab[proto.TypeConst] =
    tc match {
      case Type.Const.Defined(p, n) =>
        for {
          pidx <- getId(p.asString)
          nidx <- getId(n.ident.sourceCodeRepr)
        } yield proto.TypeConst(pidx, nidx)
    }

  def typeVarBoundToProto(tvb: Type.Var.Bound): Tab[proto.TypeVar] =
    getId(tvb.name).map(proto.TypeVar(_))

  def typeVarBoundFromProto(tv: proto.TypeVar): Tab[Type.Var.Bound] =
    lookup(tv.varName, s"typevar: $tv").map(Type.Var.Bound(_))

  def typeFromProto(p: proto.Type): Tab[Type] = {
    import proto.Type.Value
    import bosatsu.TypedAst.{Type => _, _}

    p.value match {
      case Value.Empty =>
        tabFail(new Exception("empty type found"))
      case Value.TypeConst(tc) =>
        typeConstFromProto(tc).map(Type.TyConst(_))
      case Value.TypeVar(tv) =>
        typeVarBoundFromProto(tv).map(Type.TyVar(_))
      case Value.TypeForAll(TypeForAll(args, in)) =>
        for {
          inProto <- lookupType(in, s"TypeForAll($args, $in)")
          in <- typeFromProto(inProto)
          args <- args.toList.traverse { id =>
              lookup(id, s"$id in $p").map(Type.Var.Bound(_))
            }
        } yield Type.forAll(args, in)

      case Value.TypeApply(TypeApply(left, right)) =>
        lookupType(left, s"left in TypeApply($left, $right)")
          .product(lookupType(right, s"right in TypeApply($left, $right)"))
          .flatMap { case (lp, rp) =>
            (typeFromProto(lp), typeFromProto(rp)).mapN(Type.TyApply(_, _))
          }
    }
  }

  def typeToProto(p: Type): Tab[(proto.Type, Int)] = {
    import proto.Type.Value
    import bosatsu.TypedAst.{Type => _, _}

    getProtoTypeTab(p)
      .flatMap {
        case Some(p) => tabPure(p)
        case None =>
          p match {
            case Type.ForAll(bs, t) =>
              typeToProto(t).flatMap { case (pt, idx) =>
                bs.toList
                  .traverse { b => getId(b.name) }
                  .flatMap { ids =>
                    getTypeId(p, proto.Type(Value.TypeForAll(TypeForAll(ids, idx))))
                  }
              }
            case Type.TyApply(on, arg) =>
              typeToProto(on)
                .product(typeToProto(arg))
                .flatMap { case ((lp, li), (rp, ri)) =>
                  getTypeId(p, proto.Type(Value.TypeApply(TypeApply(li, ri))))
                }
            case Type.TyConst(tcd) =>
              typeConstToProto(tcd)
                .flatMap { pt =>
                  getTypeId(p, proto.Type(Value.TypeConst(pt)))
                }
            case Type.TyVar(Type.Var.Bound(n)) =>
              getId(n).flatMap { id =>
                getTypeId(p, proto.Type(Value.TypeVar(TypeVar(id))))
              }
            case Type.TyVar(Type.Var.Skolem(_, _)) | Type.TyMeta(_) =>
              tabFail(new Exception(s"invalid type to serialize: $p"))
          }
      }
  }

  def varianceToProto(v: Variance): proto.Variance =
    v match {
      case Variance.Phantom => proto.Variance.Phantom
      case Variance.Covariant => proto.Variance.Covariant
      case Variance.Contravariant => proto.Variance.Contravariant
      case Variance.Invariant => proto.Variance.Invariant
    }

  def varianceFromProto(p: proto.Variance): Try[Variance] =
    p match {
      case proto.Variance.Phantom => Success(Variance.Phantom)
      case proto.Variance.Covariant => Success(Variance.Covariant)
      case proto.Variance.Contravariant => Success(Variance.Contravariant)
      case proto.Variance.Invariant => Success(Variance.Invariant)
      case proto.Variance.Unrecognized(value) => Failure(new Exception(s"unrecognized value for variance: $value"))
    }

  def definedTypeToProto(d: DefinedType[Variance]): Tab[proto.DefinedType] =
    typeConstToProto(d.toTypeConst).flatMap { tc =>
      def paramToProto(tv: (Type.Var.Bound, Variance)): Tab[proto.TypeParam] =
        typeVarBoundToProto(tv._1)
          .map { tvb =>
            proto.TypeParam(Some(tvb), varianceToProto(tv._2))
          }

      val protoTypeParams: Tab[List[proto.TypeParam]] =
        d.annotatedTypeParams.traverse(paramToProto)

      val constructors: Tab[List[proto.ConstructorFn]] =
        d.constructors.traverse { case (c, tp, _) =>
          tp.traverse { case (b, t) =>
            typeToProto(t).flatMap { case (_, tidx) =>
              getId(b.sourceCodeRepr)
                .map { n =>
                  proto.FnParam(n, tidx)
                }
            }
          }
          .flatMap { params =>
            getId(c.asString)
              .map { id =>
                proto.ConstructorFn(id, params)
              }
          }
        }

      (protoTypeParams, constructors)
        .mapN(proto.DefinedType(Some(tc), _, _))
    }

  def definedTypeFromProto(pdt: proto.DefinedType): Tab[DefinedType[Variance]] = {
    def paramFromProto(tp: proto.TypeParam): Tab[(Type.Var.Bound, Variance)] =
      tp.typeVar match {
        case None => tabFail(new Exception(s"expected type variable in $tp"))
        case Some(tv) =>
          typeVarBoundFromProto(tv)
            .product(lift(varianceFromProto(tp.variance)))
      }

    def fnParamFromProto(p: proto.FnParam): Tab[(Identifier.Bindable, Type)] =
      for {
        name <- lookup(p.name, p.toString)
        bn <- lift(Try(Identifier.unsafeParse(Identifier.bindableParser, name)))
        protoType <- lookupType(p.typeOf, s"invalid type id: $p")
        tpe <- typeFromProto(protoType)
      } yield (bn, tpe)

    def consFromProto(
      tc: Type.Const.Defined,
      tp: List[Type.Var.Bound],
      c: proto.ConstructorFn): Tab[(Identifier.Constructor, List[(Identifier.Bindable, Type)], Type)] =
      lookup(c.name, c.toString)
        .flatMap { cname =>
          lift(Try(Identifier.unsafeParse(Identifier.consParser, cname)))
            .flatMap { cname =>
              //def
              c.params.toList.traverse(fnParamFromProto)
                .map { fnParams =>
                  val fnType = DefinedType.constructorValueType(tc.packageName, tc.name, tp, fnParams.map(_._2))
                  (cname, fnParams, fnType)
                }
            }
        }

    pdt.typeConst match {
      case None => tabFail(new Exception(s"missing typeConst: $pdt"))
      case Some(tc) =>
        for {
          tconst <- typeConstFromProto(tc)
          tparams <- pdt.typeParams.toList.traverse(paramFromProto)
          cons <- pdt.constructors.toList.traverse(consFromProto(tconst, tparams.map(_._1), _))
        } yield DefinedType(tconst.packageName, tconst.name, tparams, cons)
    }
  }

  def interfaceToProto(iface: Package.Interface): Try[proto.Interface] = {
    val allDts = DefinedType.listToMap(
      iface.exports.flatMap { ex =>
        ex.tag match {
          case Referant.Value(_) => Nil
          case Referant.DefinedT(dt) => dt :: Nil
          case Referant.Constructor(_, dt, _, _) => dt :: Nil
        }
      }).mapWithIndex { (dt, idx) => (dt, idx) }

    val tryProtoDts = allDts
      .traverse { case (dt, _) => definedTypeToProto(dt) }
      .map(_.iterator.map(_._2).toList)

    def expNameToProto(e: ExportedName[Referant[Variance]]): Tab[proto.ExportedName] = {
      val protoRef: Tab[proto.Referant] =
        e.tag match {
          case Referant.Value(t) =>
            typeToProto(t).map { case (_, tpeId) =>
              proto.Referant(proto.Referant.Referant.Value(tpeId))
            }
          case Referant.DefinedT(dt) =>
            val key = (dt.packageName, dt.name)
            allDts.get(key) match {
              case Some((_, idx)) =>
                tabPure(
                  proto.Referant(proto.Referant.Referant.DefinedTypePtr(idx + 1))
                )
              case None => tabFail(new Exception(s"missing defined type for $key in $e"))
            }
          case Referant.Constructor(nm, dt, _, _) =>
            val key = (dt.packageName, dt.name)
            allDts.get(key) match {
              case Some((dtV, dtIdx)) =>
                val cIdx = dtV.constructors.indexWhere { case (c, _, _) => c == nm }
                if (cIdx >= 0) {
                  tabPure(
                    proto.Referant(
                      proto.Referant.Referant.Constructor(
                        proto.ConstructorPtr(dtIdx + 1, cIdx + 1))))
                }
                else tabFail(new Exception(s"missing contructor for type $key, $nm, with local: $dt"))
              case None => tabFail(new Exception(s"missing defined type for $key in $e"))
            }
        }
      val exKind: Tab[(Int, proto.ExportKind)] = e match {
        case ExportedName.Binding(b, _) =>
          getId(b.sourceCodeRepr).map((_, proto.ExportKind.Binding))
        case ExportedName.TypeName(n, _) =>
          getId(n.asString).map((_, proto.ExportKind.TypeName))
        case ExportedName.Constructor(n, _) =>
          getId(n.asString).map((_, proto.ExportKind.ConstructorName))
      }

      (protoRef, exKind).mapN { case (ref, (idx, k)) => proto.ExportedName(k, idx, Some(ref)) }
    }

    val tryExports = iface.exports.traverse(expNameToProto)

    val packageId = getId(iface.name.asString)

    val last = packageId.product(tryProtoDts).product(tryExports)

    runTab(last).map { case (serstate, ((nm, dts), exps)) =>
      proto.Interface(serstate.stringVec, nm, serstate.typeVec, dts, exps)
    }
  }

  private def referantFromProto[A](dts: Vector[DefinedType[A]], ref: proto.Referant): Tab[Referant[A]] = {
    def getDt(idx: Int): Try[DefinedType[A]] = {
      // idx is 1 based:
      val fixedIdx = idx - 1
      if ((fixedIdx < 0) || (fixedIdx >= dts.size))
        Failure(new Exception(s"invalid index: $idx in $ref, size: ${dts.size}"))
      else Success(dts(fixedIdx))
    }

    ref.referant match {
      case proto.Referant.Referant.Value(t) =>
        for {
          pt <- lookupType(t, s"invalid type in $ref")
          tpe <- typeFromProto(pt)
        } yield Referant.Value(tpe)
      case proto.Referant.Referant.DefinedTypePtr(idx) =>
        lift(getDt(idx).map(Referant.DefinedT(_)))
      case proto.Referant.Referant.Constructor(proto.ConstructorPtr(dtIdx, cIdx)) =>
        lift(getDt(dtIdx)).flatMap { dt =>
          // cIdx is 1 based:
          val fixedIdx = cIdx - 1
          dt.constructors.get(fixedIdx.toLong) match {
            case None =>
              tabFail(new Exception(s"invalid constructor index: $cIdx in: $dt"))
            case Some((c, a, t)) =>
              tabPure(Referant.Constructor(c, dt, a, t))
          }
        }
      case proto.Referant.Referant.Empty => tabFail(new Exception(s"empty referant found: $ref"))
    }
  }

  private def exportedNameFromProto[A](dts: Vector[DefinedType[A]], en: proto.ExportedName): Tab[ExportedName[Referant[A]]] = {
    val tryRef: Tab[Referant[A]] = en.referant match {
      case Some(r) => referantFromProto(dts, r)
      case None => tabFail(new Exception(s"missing referant in $en"))
    }

    tryRef.product(lookup(en.name, en.toString))
      .flatMap { case (ref, n) =>
        en.exportKind match {
          case proto.ExportKind.Binding =>
            tabPure(ExportedName.Binding(Identifier.unsafeParse(Identifier.bindableParser, n), ref))
          case proto.ExportKind.TypeName =>
            tabPure(ExportedName.TypeName(Identifier.unsafeParse(Identifier.consParser, n), ref))
          case proto.ExportKind.ConstructorName =>
            tabPure(ExportedName.Constructor(Identifier.unsafeParse(Identifier.consParser, n), ref))
          case proto.ExportKind.Unrecognized(idx) =>
            tabFail(new Exception(s"unknown export kind: $idx in $en"))
        }
      }
  }

  def interfaceFromProto(protoIface: proto.Interface): Try[Package.Interface] = {
    val consts = protoIface.constants.toVector
    val tab = lookup(protoIface.packageName, protoIface.toString)
      .flatMap { packageName =>
        PackageName.parse(packageName) match {
          case None =>
            tabFail[String, Package.Interface](
              new Exception(s"bad package name: $packageName in: $protoIface"))
          case Some(pn) =>
            protoIface
              .definedTypes
              .toVector
              .traverse(definedTypeFromProto)
              .flatMap { dtVect =>
                val exports: Tab[List[ExportedName[Referant[Variance]]]] =
                  protoIface.exports.toList.traverse(exportedNameFromProto(dtVect, _))

                exports.map { exports =>
                  Package(pn, Nil, exports, ())
                }
              }
        }
      }
    runLookupTab(consts, protoIface.types.toVector, tab)
  }

  def interfacesToProto[F[_]: Foldable](ps: F[Package.Interface]): Try[proto.Interfaces] =
    ps.toList.traverse(interfaceToProto).map { ifs =>
      // sort so we are deterministic
      proto.Interfaces(ifs.sortBy { iface => iface.constants(iface.packageName - 1) })
    }

  def interfacesFromProto(ps: proto.Interfaces): Try[List[Package.Interface]] =
    ps.interfaces.toList.traverse(interfaceFromProto)

  def readInterfaces(path: Path): IO[List[Package.Interface]] =
    IO {
      val f = path.toFile
      val ios = new BufferedInputStream(new FileInputStream(f))
      try proto.Interfaces.parseFrom(ios)
      finally {
        ios.close
      }
    }.flatMap { proto => IO.fromTry(interfacesFromProto(proto)) }

  def writeInterfaces(interfaces: List[Package.Interface], path: Path): IO[Unit] =
    IO.fromTry(interfacesToProto(interfaces))
      .flatMap { protoIfs =>
        IO {
          val f = path.toFile
          val os = new BufferedOutputStream(new FileOutputStream(f))
          try protoIfs.writeTo(os)
          finally {
            os.close
          }
        }
      }
}

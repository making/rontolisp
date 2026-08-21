package am.ik.rontolisp;

/**
 * The synthetic mito-shape program shared by the three backend suites and mirrored by the
 * {@code mop-widening-for-mito} ci-spec case: e-c-u-c :around superclass injection +
 * initialize-instance :around initarg munging (on the metaclass AND on a slot-definition
 * class) + custom slot-definition classes with an extra col-type slot + initfunction
 * readback + same-name redefinition. The print/read tail differs per harness; the answer
 * is identical on all four backends.
 */
public final class MopWideningFixture {

	private MopWideningFixture() {
	}

	/** The definition half of the program (through the redefining second defclass). */
	public static final String MITO_SHAPE_SOURCE = """
			(defclass mt-dao-class () ())
			(defclass mt-table-class (standard-class)
			  ((table-name :initarg :table-name :initform nil)
			   (pk :initarg :primary-key :initform nil)
			   (col-count)))
			(defclass mt-column (closer-mop:standard-direct-slot-definition)
			  ((col-type :initarg :col-type :accessor mt-col-type)))
			(defclass mt-eff-column (closer-mop:standard-effective-slot-definition)
			  ((col-type :initarg :col-type :initform nil :accessor mt-col-type)))
			(defmethod closer-mop:direct-slot-definition-class ((class mt-table-class) &rest initargs)
			  (find-class 'mt-column))
			(defmethod closer-mop:effective-slot-definition-class ((class mt-table-class) &rest initargs)
			  (find-class 'mt-eff-column))
			(defmethod closer-mop:compute-effective-slot-definition :around ((class mt-table-class) name dsds)
			  (let ((result (call-next-method)))
			    (setf (mt-col-type result)
			          (some (lambda (d) (if (slot-boundp d 'col-type) (mt-col-type d) nil)) dsds))
			    result))
			(defmethod initialize-instance :around ((slot mt-column) &rest rest-initargs
			                                        &key initargs &allow-other-keys)
			  (if (member ':extra initargs)
			      nil
			      (push ':extra (getf rest-initargs :initargs)))
			  (apply #'call-next-method slot rest-initargs))
			(defmethod initialize-instance :around ((class mt-table-class) &rest initargs
			                                        &key direct-superclasses &allow-other-keys)
			  (if (member (find-class 'mt-dao-class) direct-superclasses)
			      nil
			      (push (find-class 'mt-dao-class) (getf initargs :direct-superclasses)))
			  (let ((class (apply #'call-next-method class initargs)))
			    (setf (slot-value class 'col-count) (length (%obj-ref class 2)))
			    class))
			(defmethod reinitialize-instance :around ((class mt-table-class) &rest initargs)
			  (let ((class (apply #'call-next-method class initargs)))
			    (setf (slot-value class 'col-count) (length (%obj-ref class 2)))
			    class))
			(defvar *mt-ecuc* 0)
			(defmethod closer-mop:ensure-class-using-class :around ((class mt-table-class) name &rest keys
			                                                        &key direct-superclasses &allow-other-keys)
			  (setq *mt-ecuc* (+ *mt-ecuc* 1))
			  (if (member (find-class 'mt-dao-class) direct-superclasses)
			      nil
			      (setf (getf keys :direct-superclasses)
			            (cons (find-class 'mt-dao-class) direct-superclasses)))
			  (apply #'call-next-method class name keys))
			(defclass mt-user ()
			  ((id :initarg :id :col-type :serial :reader mt-user-id)
			   (name :initarg :name :col-type :text :initform "anon" :reader mt-user-name))
			  (:metaclass mt-table-class)
			  (:table-name "users")
			  (:primary-key id))
			(defvar *mt-first*
			  (let ((c (find-class 'mt-user)))
			    (list *mt-ecuc*
			          (mapcar (lambda (s) (%obj-ref s 0)) (%obj-ref c 1))
			          (slot-value c 'table-name)
			          (slot-value c 'pk)
			          (slot-value c 'col-count)
			          (mapcar (lambda (s) (%obj-ref s 1)) (%obj-ref c 2))
			          (mapcar (lambda (s) (let ((f (%obj-ref s 5)))
			                                (if f (funcall f) ':none)))
			                  (%obj-ref c 3))
			          (mapcar #'mt-col-type (%obj-ref c 3))
			          (mapcar (lambda (s) (%obj-ref s 0)) (%class-direct-subclasses (find-class 'mt-dao-class))))))
			(defclass mt-user ()
			  ((id :initarg :id :col-type :serial :reader mt-user-id)
			   (email :initarg :email :col-type :text :reader mt-user-email))
			  (:metaclass mt-table-class)
			  (:table-name "users2"))
			""";

	/** The value the shared read tail answers, identical on all four backends. */
	public static final String MITO_SHAPE_EXPECTED = "((0 (MT-DAO-CLASS) (\"users\") (ID) 2 ((:EXTRA :ID) (:EXTRA :NAME))"
			+ " (:NONE \"anon\") (:SERIAL :TEXT) (MT-USER)) (1 (\"users2\") (ID EMAIL) 2 1))";

}

import React, { useState, useEffect, useRef } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import ParticipationForm from '../../components/meeting/ParticipationForm';
import './MeetingDetail.css';
import api from '../../api/axios';

const formatDateTime = (dateString) => {
    const options = {
        year: 'numeric',
        month: 'long',
        day: 'numeric',
        weekday: 'short',
        hour: '2-digit',
        minute: '2-digit'
    };
    return new Date(dateString).toLocaleTimeString('ko-KR', options);
};

const MeetingDetail = () => {
    const { id } = useParams();
    const navigate = useNavigate();
    const [meeting, setMeeting] = useState(null);
    const [loading, setLoading] = useState(true);
    const [showForm, setShowForm] = useState(false);
    const formRef = useRef(null);

    const isCreator = meeting?.isHost;

    useEffect(() => {
        const fetchMeetingDetail = async () => {
            try {
                const res = await api.get(`/meetings/${id}`);
                console.log("------- [API Response Data] -------");
                console.log(res.data);
                setMeeting(res.data);
            } catch (err) {
                console.error("상세 데이터 로딩 실패:", err);
                navigate('/', { replace: true });
            } finally {
                setLoading(false);
            }
        };
        fetchMeetingDetail();
    }, [id, navigate]);

    // 삭제 처리
    const handleDelete = async () => {
        if (!window.confirm("정말로 이 모임을 삭제하시겠습니까?")) return;
        try {
            await api.delete(`/meetings/${id}`);
            alert("삭제되었습니다.");
            navigate('/');
        } catch (err) {
            console.error("삭제 실패:", err);
            alert("삭제 권한이 없거나 오류가 발생했습니다.");
        }
    };

    const handleApplyClick = () => {
        const token = localStorage.getItem('accessToken');
        if (!token) {
            alert("참여 신청을 하려면 로그인이 필요합니다.");
            navigate('/login');
            return;
        }
        setShowForm(true);
        setTimeout(() => {
            formRef.current?.scrollIntoView({ behavior: 'smooth' });
        }, 100);
    };

    if (loading) return <div className="loading-container">데이터를 불러오는 중입니다...</div>;
    if (!meeting) return null;

    const isFull = meeting.currentParticipants >= meeting.capacity;

    return (
        <div className="meeting-page-bg">
            <div className="meeting-container">
                <div className="meeting-header">
                    <div className="header-top">
                            <span className="meeting-category-tag">{meeting.categoryName}</span>
                            {/* 💡 서버가 '너 방장 맞아'라고 하면 버튼 노출 */}
                            {isCreator && (
                                <div className="creator-actions">
                                    <button className="btn-edit-text" onClick={() => navigate(`/meetings/edit/${id}`)}>수정</button>
                                    <button className="btn-delete-text" onClick={handleDelete}>삭제</button>
                                </div>
                            )}
                            </div>
                        <div>
                    </div>
                    <h1 className="meeting-title">{meeting.title}</h1>
                    <div className="meeting-meta">
                        <span>작성자: <strong>{meeting.creatorNickname}</strong></span>
                        <span className="meta-divider">|</span>
                        <span>작성일: {new Date(meeting.createdAt).toLocaleDateString()}</span>
                    </div>
                </div>

                <div className="meeting-stats-board">
                    <div className="stat-card">
                        <label className="stat-label">모집 현황</label>
                        <div className="stat-value">
                            <span className={isFull ? "text-full" : "text-open"}>
                                {meeting.currentParticipants}
                            </span>
                            <span className="text-total"> / {meeting.capacity} 명</span>
                        </div>
                    </div>
                    <div className="stat-card">
                        <label className="stat-label">조회수</label>
                        <div className="stat-value">
                            <span>{meeting.viewCount}</span>
                            <span className="unit"> 회</span>
                        </div>
                    </div>
                    <div className="stat-card full-width">
                        <label className="stat-label">모임 일정</label>
                        <div className="stat-value date-range">
                            <span className="date-text">{formatDateTime(meeting.startDate)}</span>
                            <span className="date-separator">~</span>
                            <span className="date-text">{formatDateTime(meeting.endDate)}</span>
                        </div>
                    </div>
                </div>

                <div className="meeting-content-section">
                    <h3 className="content-label">모임 상세 설명</h3>
                    <div className="meeting-content">{meeting.description}</div>
                </div>

                <div className="meeting-footer">
                    {!isCreator ? (
                        !showForm ? (
                            <button
                                className={`btn-apply-main ${isFull ? "disabled" : ""}`}
                                disabled={isFull}
                                onClick={handleApplyClick}
                            >
                                {isFull ? "모집이 완료되었습니다" : "이 모임에 참여하기"}
                            </button>
                        ) : (
                            <div className="apply-notice-box">아래 신청서를 작성해주세요 👇</div>
                        )
                    ) : (
                        <div className="creator-notice-box">내가 작성한 모집글입니다.</div>
                    )}
                </div>

                {showForm && !isCreator && (
                    <div ref={formRef} className="form-section-wrapper">
                        <ParticipationForm meetingPostId={id} />
                        <button className="btn-cancel-action" onClick={() => setShowForm(false)}>신청 취소하기</button>
                    </div>
                )}
            </div>
        </div>
    );
};

export default MeetingDetail;